#!/bin/bash
# PostToolUse: ADR 번호를 적으면 그 ADR의 **정본 제목**을 되울린다.
#
# 왜 필요한가 — ADR은 "뒤집을 때만 읽는다"는 포인터 전략이라, 에이전트는 늘 progress.md·design·
# 코드 주석의 **2차 요약**으로 일한다. 그 상태에서 커밋 메시지·계획서·새 ADR에 번호를 적으면
# **열지 않은 인용**이 1차 문서로 굳고, 다음 세션은 그것을 근거로 믿는다.
#
# 2026-08-09 실측: 한 세션이 계획서에 `ADR-0026`을 열지 않고 인용했다. 번호는 맞았지만
# 0025와 0027 사이를 메운 추정이었다. `CLAUDE.md`가 "자율의 대가로 근거를 남긴다"고 하는데,
# **위조된 근거는 진짜와 화면상 완전히 같다.** 이 훅은 그 구분을 만든다.
#
# 다른 훅들과 성격이 다르다 — 나머지가 *입력*(알아야 할 것)을 보강하는 반면 이것은
# *출력*(남긴 주장)을 검증한다. 막지는 않는다. 제목을 들이밀 뿐이고, 제목과 주장이 어긋나는지는
# 에이전트가 본다.
#
# `remind-adr.sh`에 얹지 않고 분리한 이유: 그 파일이 두 방향을 합친 근거는 "경로 추출·중복
# 억제·발신을 공유하기 때문"인데 이 훅은 **경로를 쓰지 않는다**(내용에서 번호를 뽑는다).
# 게다가 matcher에 Bash를 더하면 근거 계층을 지탱하는 유일한 훅의 제어 흐름을 부수적 목적으로
# 건드리게 되고, 그것이 깨지면 증상은 침묵이다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
session=$(echo "$input" | jq -r '.session_id // "nosession"')

# MUST: 필드를 열거하지 않고 tool_input 전체를 문자열로 훑는다.
# Write는 content, Edit는 new_string, Bash는 command이고 앞으로 다른 도구가 또 다른 필드를
# 쓸 수 있다 — 열거하면 빠뜨리고, 빠뜨린 것은 침묵한다.
payload=$(echo "$input" | jq -r '.tool_input // {} | tostring' 2>/dev/null || echo "")
[ -z "$payload" ] && exit 0

ids=$(printf '%s' "$payload" | grep -oE 'ADR-[0-9]{4}' | sort -u || true)
[ -z "$ids" ] && exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
adr_dir="${root}/docs/adr"
marker_dir="${TMPDIR:-/tmp}/claude-harness-hooks/${session}/echo-adr-citation"

# ADR 번호 단위로 세션당 1회. 마커를 만들 수 없으면 중복 억제를 포기하고 알린다 —
# 알림 훅은 침묵이 아니라 발화 쪽으로 실패한다.
already_fired() {
  local key marker
  key=$(echo "$1" | shasum 2>/dev/null | cut -d' ' -f1 || echo "")
  [ -z "$key" ] && return 1
  marker="${marker_dir}/${key}"
  mkdir -p "$marker_dir" 2>/dev/null || return 1
  [ -f "$marker" ] && return 0
  touch "$marker" 2>/dev/null || true
  return 1
}

known=""
unknown=""
while IFS= read -r id; do
  [ -z "$id" ] && continue
  already_fired "$id" && continue

  # 파일 하나만 본다. remind-adr.sh처럼 전체를 awk로 훑을 이유가 없다.
  file=""
  for candidate in "${adr_dir}/${id}"-*.md; do
    [ -f "$candidate" ] && { file="$candidate"; break; }
  done

  if [ -z "$file" ]; then
    unknown="${unknown}${id} "
    continue
  fi

  # `# ADR-0026. 제목` → `제목`
  title=$(grep -m1 "^#[[:space:]]*${id}\." "$file" 2>/dev/null | sed "s/^#[[:space:]]*${id}\.[[:space:]]*//" || true)
  [ -z "$title" ] && title="(제목을 읽지 못했다)"
  [ -n "$known" ] && known="${known}"$'\n'
  known="${known}  ${id} — ${title} (docs/adr/$(basename "$file"))"
done <<< "$ids"

[ -z "$known" ] && [ -z "$unknown" ] && exit 0

# MUST NOT: 매칭된 주변 문맥을 되울리지 않는다. 번호와 정본 제목만 낸다.
# tool_input 전체를 훑기 때문에, 문맥을 함께 내면 시크릿이 든 Bash 명령이 컨텍스트와
# 트랜스크립트로 반사된다. "친절하게" 문맥을 붙이려는 유혹이 발생점이다.
msg=""
if [ -n "$known" ]; then
  msg="이번 기록이 인용한 ADR의 정본 제목이다."$'\n'"${known}"$'\n'"제목과 적은 내용이 어긋나면 그 ADR을 열고 고쳐라. 열지 않고 번호만 맞춘 인용은 다음 세션이 근거로 믿는다."
fi
if [ -n "$unknown" ]; then
  [ -n "$msg" ] && msg="${msg}"$'\n\n'
  msg="${msg}존재하지 않는 ADR을 인용했다: ${unknown}— docs/adr/에 그 번호가 없다. 앞으로 만들 번호를 미리 적은 것이면 그대로 두고, 오기면 지금 고쳐라."
fi

jq -n --arg ctx "$msg" \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
