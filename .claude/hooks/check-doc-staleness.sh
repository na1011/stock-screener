#!/bin/bash
# SessionStart guard: 문서의 시점 의존적 주장과 실제 코드 상태를 대조해 불일치만 보고한다.
# 차단하지 않는다. 불일치가 없으면 아무것도 출력하지 않고 조용히 통과한다.
set -euo pipefail

# jq가 없으면 이 검사만 죽는 게 아니라 guard-secrets.sh의 시크릿 판정도 함께 죽는다.
# 그 사실을 먼저 알린다.
#
# 여기서 평문 stdout을 쓰는 이유는 하나뿐이다 — jq에 의존하는 경로로는 jq 부재를 알릴 수 없다.
# SessionStart의 평문 stdout은 그대로 컨텍스트로 들어가므로 jq가 필요 없다.
# 아래 findings 보고가 jq -n을 쓰는 것과 달라 보이지만, 그쪽은 jq가 있을 때만 도달한다.
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 없다. guard-secrets.sh의 시크릿 차단과 이 문서 검사가 모두 무력화된 상태다. .env.production·*.key·credentials.json 같은 경로는 permissions가 덮지 않으므로 지금은 아무것도 막지 않는다. jq를 설치하면 복구된다."
  exit 0
fi

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0

findings=""
add() { findings="${findings}- $1"$'\n'; }

# 1. progress.md가 주입 상한을 넘겨 세션 시작 주입이 잘리는가
#
# 이 검사가 없던 동안 실제로 잘렸다. inject-progress.sh는 잘렸다는 사실을 주입문 끝에
# 덧붙이지만, 그 문구 자체가 잘린 문서 뒤에 있어 사람 눈에 잘 띄지 않는다. 결과는
# "뒷부분이 다음 세션에 전달되지 않는데 아무도 모르는" 상태다 — 이 저장소가 가장 경계하는
# 조용한 실패다. 세션 시작에 한 줄로 보고해 그것을 깬다.
#
# 문자 수로 센다. inject-progress.sh가 ${#content}로 자르는데 그것이 바이트가 아니라
# 문자임을 실측 확인했다(8,000자 지점에서 정확히 끊겼다). 바이트로 세면 한글 문서에서
# 상한이 3배 엄격해져 매 세션 오탐이 되고, 오탐이 반복되면 이 검사도 무시된다.
inject_hook=".claude/hooks/inject-progress.sh"
if [ -f docs/progress.md ] && [ -f "$inject_hook" ]; then
  # 상한을 이 파일에 복제하지 않고 주입 훅에서 읽는다. 두 곳에 적으면 한쪽만 바뀌고,
  # 그때 이 검사는 통과하는데 주입은 잘리는 최악의 조합이 된다.
  inject_limit=$(grep -oE '^limit=[0-9]+' "$inject_hook" 2>/dev/null | head -1 | cut -d= -f2)
  if [ -n "${inject_limit:-}" ]; then
    # 경고선을 절단선의 절반에 둔다. 둘이 같은 지점이면 이미 손상된 뒤에야 울린다 —
    # 그때는 뒷부분이 이번 세션에 전달되지 않은 상태이고, 무엇이 잘렸는지도 모른다.
    # 별도 상수를 두지 않고 절단선에서 계산해, 한쪽만 바뀌는 조합을 애초에 만들지 않는다.
    warn_limit=$(( inject_limit / 2 ))
    len=$(wc -m < docs/progress.md 2>/dev/null | tr -d ' ')
    if [ -n "$len" ] && [ "$len" -gt "$inject_limit" ]; then
      add "docs/progress.md가 ${len}자로 주입 상한(${inject_limit}자)을 넘는다. 세션 시작 주입이 이미 잘렸고, 뒷부분은 이번 세션에 전달되지 않았다. 파일을 직접 읽고 줄여라."
    elif [ -n "$len" ] && [ "$len" -gt "$warn_limit" ]; then
      add "docs/progress.md가 ${len}자로 경고선(${warn_limit}자)을 넘었다. 절단은 ${inject_limit}자부터다. 줄을 더하기 전에 뺄 것을 먼저 정하라 — 고정 예산이지 로그가 아니다. 파일별 함정은 \`.claude/rules/\`로, 작업 절차는 skill로 내린다."
    fi
  fi
fi

# 2. 프론트엔드 부재 주장 vs 실제
if [ -d frontend ] || [ -d src/main/frontend ] || [ -d src/main/resources/static/src ] \
   || [ -n "$(find . -name '*.vue' -not -path './.git/*' -not -path './build/*' -print -quit 2>/dev/null)" ]; then
  if grep -qsE '프론트엔드 (디렉터리|디렉토리)?( 없|.*아직)' docs/progress.md README.md; then
    add "진행 상태/README가 프론트엔드 부재를 주장하지만 실제 프론트엔드 파일·디렉터리가 있다."
  fi
fi

# 3. ADR 문서가 필수 필드(applies_to / 검증)를 갖췄는가
# 실제 ADR은 ADR-NNNN- 접두어를 쓰므로 0000-template.md는 자연히 제외된다.
if [ -d docs/adr ]; then
  incomplete=""
  while IFS= read -r adr; do
    [ -z "$adr" ] && continue
    missing=""
    grep -q '^applies_to:' "$adr" || missing="applies_to "
    grep -q '^## 검증'     "$adr" || missing="${missing}검증"
    [ -n "$missing" ] && incomplete="${incomplete}$(basename "$adr")(${missing}) "
  done <<< "$(find docs/adr -maxdepth 1 -name 'ADR-[0-9][0-9][0-9][0-9]-*.md' 2>/dev/null)"
  if [ -n "$incomplete" ]; then
    add "필수 항목이 빠진 ADR이 있다: ${incomplete}— applies_to 없이는 관련 작업에서 로드되지 않고, 검증 절 없이는 준수 여부를 확인할 수 없다."
  fi
fi

# 4. 규칙의 paths와 ADR의 applies_to glob이 실제 파일과 매칭되는가
#
# 매칭되지 않는 glob은 규칙을 **영원히 로드되지 않게** 만들고, 로드되지 않았다는 사실은
# 어디에도 드러나지 않는다. 실제로 frontend.md의 `**/*.js`·`**/*.scss`가 그 상태였다.
#
# **ADR도 같은 성질이다.** `applies_to`가 아무것도 매칭하지 않으면 `remind-adr.sh`의 참조
# 방향이 그 ADR에 대해 영구히 침묵한다 — 위 3번은 필드의 **존재**만 보므로 통과한다.
# 근거 계층이 그 ADR에 대해 죽는 것이고, 죽었다는 신호가 없다.
#
# 파일 단위로 본다 — 어느 glob이든 하나 매칭되면 통과다. glob 단위로 가면
# `application-*.yaml`처럼 오늘 0건이지만 내일 생길 선행 glob에서 첫날부터 오탐이 나고,
# 오탐이 반복되면 이 검사 자체가 무시된다.
#
# `**`를 `*`로 접는다. bash `[[ ]]` 패턴에서 `*`는 `/`를 넘으므로 `src/main/java/**`가
# 의도대로 동작한다. IMPORTANT — **이 접기는 bash 전용이다.** 같은 대조를 zsh에서 손으로
# 흉내내면 `*`가 `/`를 넘지 않아 0건이 나온다(2026-08-09 실측: bash 47건 / zsh 0건). 그 결과를
# 믿고 멀쩡한 glob을 "고치러" 갈 수 있다. 손으로 확인할 때는 `git ls-files '<glob>'`을 쓴다.
if command -v git >/dev/null 2>&1; then
  tracked=$(git ls-files 2>/dev/null || true)
  if [ -n "$tracked" ]; then
    # 첫 인자는 목록 키(`paths:` 또는 `applies_to:`), 나머지는 검사할 파일들.
    # 매칭 실패한 파일 이름을 공백으로 이어 stdout에 낸다.
    collect_orphans() {
      local key="$1"; shift
      local doc globs matched pat f out=""
      for doc in "$@"; do
        [ -f "$doc" ] || continue
        globs=$(awk -v k="^${key}:[[:space:]]*$" '
          $0 ~ k { f = 1; next }
          f && /^[[:space:]]*-[[:space:]]*/ {
            sub(/^[[:space:]]*-[[:space:]]*/, "")
            gsub(/^["'"'"']|["'"'"']$/, "")
            gsub(/\*\*/, "*")
            # frontmatter 종료선 `---`도 목록 항목처럼 보인다. 접힌 뒤 `--`가 되는데
            # 어느 파일과도 매칭되지 않아 무해하지만, 세어서 헷갈릴 이유가 없다.
            if ($0 != "" && $0 != "-" && $0 != "--") print
            next
          }
          f { exit }
        ' "$doc" 2>/dev/null || true)
        # 목록이 없으면 이 검사 대상이 아니다 — 규칙은 매 세션 로드되고,
        # ADR의 필드 부재는 위 3번이 이미 보고한다.
        [ -z "$globs" ] && continue
        matched=false
        while IFS= read -r pat; do
          [ -z "$pat" ] && continue
          while IFS= read -r f; do
            # shellcheck disable=SC2053  # 우변을 따옴표로 감싸면 glob이 아니라 리터럴이 된다
            if [[ "$f" == $pat ]]; then matched=true; break; fi
          done <<< "$tracked"
          [ "$matched" = true ] && break
        done <<< "$globs"
        [ "$matched" = false ] && out="${out}$(basename "$doc") "
      done
      printf '%s' "$out"
    }

    if [ -d .claude/rules ]; then
      orphan_rules=$(collect_orphans paths .claude/rules/*.md)
      if [ -n "$orphan_rules" ]; then
        add "paths glob이 실제 파일과 하나도 매칭되지 않는 규칙이 있다: ${orphan_rules}— 이 규칙은 영원히 로드되지 않는다. \`git ls-files '<glob>'\`으로 glob을 맞춰라."
      fi
    fi

    if [ -d docs/adr ]; then
      orphan_adrs=$(collect_orphans applies_to docs/adr/ADR-[0-9][0-9][0-9][0-9]-*.md)
      if [ -n "$orphan_adrs" ]; then
        # "깨졌다"고 단정하지 않는다. 아직 만들지 않은 패키지를 가리키는 것은 정상일 수 있다
        # (ADR-0010처럼 미룬 결정). 다만 그동안 포인터가 침묵한다는 사실은 알아야 한다.
        add "applies_to가 지금 어떤 파일과도 매칭되지 않는 ADR이 있다: ${orphan_adrs}— 그 코드가 생길 때까지 \`remind-adr.sh\`의 포인터는 이 ADR에 대해 침묵한다. 예정된 경로면 정상이고, 오타·이름 변경이면 지금 고쳐라. 영구히 매칭될 수 없는 glob이면 아무것도 하지 않으므로 지운다."
      fi
    fi
  fi
fi

[ -z "$findings" ] && exit 0

jq -n --arg ctx "문서-코드 불일치가 감지되었다. 관련 작업을 할 때 문서를 함께 갱신하라.

${findings}" \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $ctx
    }
  }'
exit 0
