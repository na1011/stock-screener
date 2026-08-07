#!/bin/bash
# PostToolUse guard: ADR과 편집 파일을 잇는다. 두 방향이 있고 한 스크립트에 둔 이유는
# 경로 추출·중복 억제·발신을 공유하기 때문이다.
#
#   1. 기록 방향 — 되돌리기 어려운 결정이 담기기 쉬운 파일이 편집되면 ADR 작성을 상기시킨다.
#   2. 참조 방향 — 편집한 파일을 지배하는 ADR이 있으면 그것을 알린다(ADR-0020).
#
# 2번이 없던 동안 20개 ADR의 `applies_to`는 아무도 읽지 않는 필드였다. 문서 두 곳이
# "자동 로드된다"고 적고 있었지만 그것을 구현한 코드가 없었다.
#
# 차단하지 않는다 (PostToolUse는 구조적으로 차단 불가). 순수 알림이다.
# 편집 "뒤"에 오는 것으로 충분하다 — ADR이 막는 것은 오타가 아니라 결정을 되돌리는 것이고,
# 되돌리기는 다중 편집 활동이라 첫 편집 직후의 알림이 나머지 편집 앞에 선다. PreToolUse로
# 올리면 모든 Java 편집이 권한 프롬프트를 거쳐 ADR 인지가 승인 게이트가 되는데,
# 그 게이트는 `aece427`이 무인 자율 실행을 위해 이미 제거한 것이다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
session=$(echo "$input" | jq -r '.session_id // "nosession"')

[ -z "$path" ] && exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
base=$(basename "$path")
# 저장소 상대 경로. applies_to가 상대 경로로 적혀 있으므로 맞춰야 한다.
rel="${path#"${root}/"}"

marker_dir="${TMPDIR:-/tmp}/claude-adr-reminder/${session}"

# 세션 단위 중복 억제 — 같은 파일·주제에 대해 한 세션에 한 번만 발화한다.
# 저장소 전역 ADR 개수로 억제하지 않는다: 그렇게 하면 첫 ADR이 생긴 뒤 영구히 침묵해,
# 정작 기록할 결정이 쌓이기 시작하는 시점부터 리마인더가 죽는다.
# 저장소를 오염시키지 않도록 프로젝트 밖 임시 경로를 쓴다.
# shasum이 없으면 중복 억제만 포기하고 알림은 계속한다 (순수 알림이므로 발화가 우선).
already_fired() {
  local key
  key=$(echo "$1" | shasum 2>/dev/null | cut -d' ' -f1 || echo "")
  [ -z "$key" ] && return 1
  local marker="${marker_dir}/${key}"
  mkdir -p "$marker_dir" 2>/dev/null || return 0
  [ -f "$marker" ] && return 0
  touch "$marker" 2>/dev/null || true
  return 1
}

# ── 1. 기록 방향 — 새 의존성 / 저장 구조 / 외부 연동 / 인프라 구성 ──────────────
category=""
case "$base" in
  build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|libs.versions.toml)
    category="의존성" ;;
  # 프론트 의존성도 같은 성격이다. 라이선스가 바뀌거나(PrimeVue 5) 메이저가 올라가면
  # 되돌리기 비용이 build.gradle과 다르지 않은데, 여기 없어서 Phase 5 내내 발화하지 않았다.
  package.json)
    category="의존성(프론트)" ;;
  application.yaml|application.yml|application-*.yaml|application-*.yml)
    category="외부 연동·저장 구조" ;;
  Dockerfile|Dockerfile.*|compose.yaml|compose.yml|docker-compose.yaml|docker-compose.yml)
    category="인프라 구성" ;;
  *.tf|*.tfvars)
    category="인프라 구성" ;;
  V[0-9]*__*.sql|*.changelog.xml)
    category="저장 구조(마이그레이션)" ;;
esac

if [ -n "$category" ] && already_fired "${rel}|${category}"; then
  category=""
fi

# ── 2. 참조 방향 — 이 파일을 지배하는 ADR ────────────────────────────────────
#
# ADR 전체를 awk 한 번으로 훑어 `id<TAB>제목<TAB>파일명<TAB>glob` 줄을 뽑고, 매칭은 bash가 한다.
# 파일마다 프로세스를 띄우면 편집 한 번에 20 fork가 된다.
#
# glob은 `**`를 `*`로 접는다. bash의 `[[ ]]` 패턴에서 `*`는 `/`를 넘으므로
# `src/main/java/**/web/**` → `src/main/java/*/web/*`가 의도대로 동작한다.
# 템플릿(0000-template.md)은 파일명 규약으로 걸러진다 — 그 안의 applies_to는 예시다.
governing=""
adr_dir="${root}/docs/adr"
if [ -d "$adr_dir" ]; then
  while IFS=$'\t' read -r adr_id adr_title adr_file adr_glob; do
    [ -z "$adr_glob" ] && continue
    # shellcheck disable=SC2053  # 우변을 따옴표로 감싸면 glob이 아니라 리터럴이 된다
    if [[ "$rel" == $adr_glob ]]; then
      case "$governing" in
        *"${adr_id}"*) continue ;;  # 한 ADR이 여러 glob으로 걸릴 수 있다
      esac
      governing="${governing}${adr_id}"$'\t'"${adr_title}"$'\t'"${adr_file}"$'\n'
    fi
  done < <(
    awk '
      # 제목은 frontmatter 뒤에 오므로 파일을 다 읽은 뒤에야 확정된다. ENDFILE은 gawk
      # 전용이라(macOS 기본은 BSD awk) 파일 전환을 직접 감지해 이전 파일 몫을 흘린다.
      function flush(  i) {
        for (i = 1; i <= n; i++) print gid[i] "\t" title "\t" gfile[i] "\t" gpat[i]
        n = 0
      }
      FILENAME != prev {
        if (prev != "") flush()
        prev = FILENAME; id = ""; title = ""; inlist = 0
        file = FILENAME; sub(/.*\//, "", file)
      }
      /^id:[[:space:]]*/          { id = $0; sub(/^id:[[:space:]]*/, "", id) }
      /^#[[:space:]]*ADR-/        { title = $0; sub(/^[^.]*\.[[:space:]]*/, "", title) }
      /^applies_to:[[:space:]]*$/ { inlist = 1; next }
      inlist && /^[[:space:]]*-[[:space:]]*/ {
        g = $0
        sub(/^[[:space:]]*-[[:space:]]*/, "", g)
        sub(/[[:space:]]*#.*$/, "", g)
        gsub(/^["'"'"']|["'"'"']$/, "", g)
        gsub(/\*\*/, "*", g)
        if (g != "") { n++; gid[n] = id; gfile[n] = file; gpat[n] = g }
        next
      }
      inlist { inlist = 0 }
      END { if (prev != "") flush() }
    ' "$adr_dir"/ADR-*.md 2>/dev/null || true
  )
fi

if [ -n "$governing" ] && already_fired "${rel}|adr-governed"; then
  governing=""
fi

[ -z "$category" ] && [ -z "$governing" ] && exit 0

# ── 발신 ────────────────────────────────────────────────────────────────────
msg=""

if [ -n "$category" ]; then
  msg="\`${base}\` 가 변경되었다 (${category}). 이 변경이 되돌리기 어려운 결정을 담고 있다면 \`docs/adr/\`에 \`0000-template.md\` 형식으로 기록하라. 단순 버전 조정이나 포맷 변경이면 기록하지 않아도 된다. 판단은 직접 하라 — 허가를 구하지 말고 결정한 뒤 기록하라."
fi

if [ -n "$governing" ]; then
  count=$(printf '%s' "$governing" | grep -c . || true)
  # 본문이 아니라 포인터를 낸다. 본문 주입은 편집당 3~6천 자가 되어 아무것도 안 하는 것보다
  # 나쁘다. 넷 이상이면 제목을 빼고 id만 — ADR-0015·0020이 둘 다 `**/web/**`라 곧 쌓인다.
  if [ "$count" -gt 3 ]; then
    ids=$(printf '%s' "$governing" | awk -F'\t' '{printf "%s%s", sep, $1; sep=" · "}')
    ref="${ids} (docs/adr/)"
  else
    ref=$(printf '%s' "$governing" | awk -F'\t' '{printf "%s%s — %s (docs/adr/%s)", sep, $1, $2, $3; sep=" / "}')
  fi
  [ -n "$msg" ] && msg="${msg}"$'\n\n'
  # 파일명 뒤에 조사를 붙이지 않는다 — 이름이 변수라 은/는·이/가가 고정될 수 없다.
  msg="${msg}\`${base}\` — 이 파일을 지배하는 ADR이 있다: ${ref}. 여기 적힌 결정을 되돌리는 변경이라면 먼저 읽어라. 지워도 컴파일되고 테스트도 통과하는 종류다."
fi

jq -n --arg ctx "$msg" \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
