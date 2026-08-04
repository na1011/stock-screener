#!/bin/bash
# SessionEnd: 소스가 바뀐 세션이 끝날 때 docs/progress.md 갱신을 상기시킨다.
#
# 이 하네스에는 읽기 측(SessionStart 주입)만 있고 쓰기 측이 없었다.
# 갱신되지 않는 진행 상태 문서는 낡은 채로 주입되어, 다음 세션을 돕는 대신 잘못 인도한다.
# 사람은 낡은 문서를 보면 낡았다고 의심하지만 에이전트는 그대로 믿는다.
#
# 차단하지 않는다 (SessionEnd는 구조적으로 차단 불가). 순수 알림이다.
# 갱신 여부와 내용의 판단은 에이전트에게 남긴다 — 이 저장소의 알림 계층 설계 원칙과 같다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0

[ -f docs/progress.md ] || exit 0
command -v git >/dev/null 2>&1 || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

# progress.md 자체가 이미 수정되었다면 갱신한 것으로 보고 넘어간다.
git status --porcelain -- docs/progress.md 2>/dev/null | grep -q . && exit 0

# 이 세션에서 실제로 뭔가 바뀐 경우에만 상기시킨다.
# 조회만 한 세션까지 매번 알리면 신호가 무뎌진다.
# 제외는 git 자신의 pathspec으로 한다 — porcelain 출력 컬럼을 문자열로 자르면
# 파일명에 공백이나 따옴표가 섞일 때 어긋난다.
changed=$(git status --porcelain -- . ':(exclude)docs/progress.md' 2>/dev/null || true)
[ -z "$changed" ] && exit 0

jq -n --arg ctx "이 세션에서 소스가 변경되었으나 \`docs/progress.md\`는 갱신되지 않았다.

다음 세션이 알아야 할 것이 생겼다면 지금 기록하라 — phase 상태 변화, ADR로 남기기엔 사소하지만 모르면 다시 만들게 될 결정, 미뤄둔 것, 되돌리면 안 되는 판단. 아무것도 달라지지 않았다면 갱신하지 않아도 된다.

상태 주장은 확인 가능한 형태로 쓴다." \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionEnd",
      additionalContext: $ctx
    }
  }' || exit 0
exit 0
