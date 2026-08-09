#!/bin/bash
# PostToolUse: 계획 파일을 처음 기록할 때 **착수 전에** 해야 하는 두 가지를 알린다.
#
# 왜 필요한가 — `CLAUDE.md`가 "새 phase는 완료 판정을 **먼저** 적고 시작한다"를 MUST로
# 두는데, 그 **먼저**를 강제하는 것이 아무것도 없다. `docs/phase-criteria.md`가 스스로
# "끝난 뒤에 세운 기준은 이미 한 일을 정당화하는 쪽으로 기운다"고 적고 있다.
#
# 되돌리기 어려운 결정도 시점이 어긋난다. `remind-adr.sh`는 **파일을 고친 뒤** 발화하는데,
# 기획 세션에서 그 결정은 **계획서에서** 내려진다 — 코드를 아직 안 건드렸으니 그 훅은 조용하다.
#
# 기획 단계는 이 저장소 하네스에서 유일하게 기계적 트리거가 없던 구간이다. 계획 파일 기록이
# 그 트리거다. `ExitPlanMode`가 matcher로 잡히면 더 정확하지만 확인되지 않았고, 계획서 기록은
# 확실히 일어나며 **더 이르다**(마지막이 아니라 첫 기록에서 발화한다).
#
# 계획 파일이 저장소 밖(`~/.claude/plans/`)이라 `paths` glob으로는 덮을 수 없다. 훅은 경로를
# 입력으로 받으므로 가능하다.
#
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
session=$(echo "$input" | jq -r '.session_id // "nosession"')
[ -z "$path" ] && exit 0

case "$path" in
  */plans/*.md) ;;
  *) exit 0 ;;
esac

marker_dir="${TMPDIR:-/tmp}/claude-harness-hooks/${session}/remind-plan-obligations"
if mkdir -p "$marker_dir" 2>/dev/null; then
  [ -f "${marker_dir}/fired" ] && exit 0
  touch "${marker_dir}/fired" 2>/dev/null || true
fi

jq -n --arg ctx "계획을 기록했다. 착수 전에 두 가지를 본다 — 둘 다 나중에 하면 늦는다.
- **새 phase라면** 완료 판정을 \`docs/phase-criteria.md\`에 **먼저** 적는다. 끝난 뒤에 세운 기준은 이미 한 일을 정당화하는 쪽으로 기운다.
- **새 의존성·저장 구조·외부 연동·인프라 구성**이 계획에 있으면 ADR을 남길 지점을 계획에 포함한다. \`remind-adr.sh\`는 파일을 고친 뒤에야 발화하므로 지금은 조용하다.
- **실측이 결정의 근거라면** 그 측정을 ADR \`## 검증\`의 \`premise\` 펜스로 남긴다. 다음 세션이 같은 것을 다시 재지 않고, 값이 변하면 세션 시작에 알려 준다. 형식은 \`docs/adr/0000-template.md\`에 있다." \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
