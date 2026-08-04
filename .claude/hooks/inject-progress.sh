#!/bin/bash
# SessionStart: 진행 상태(docs/progress.md)를 세션 시작 시 주입한다.
#
# 저장소에 파일이 있는 것만으로는 부족하다. "다음 세션이 알아서 찾아 읽겠지"에 기대면
# 실제로는 읽지 않는다 — 새 세션은 무엇을 모르는지조차 모르기 때문이다.
# additionalContext는 첫 프롬프트 이전에 주입되므로 확실하다.
#
# 차단하지 않는다. 파일이 없거나 jq가 없으면 조용히 통과한다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || exit 0

progress="docs/progress.md"
[ -f "$progress" ] || exit 0

content=$(cat "$progress" 2>/dev/null) || exit 0
[ -z "$content" ] && exit 0

# additionalContext는 10,000자 상한이다. 안내 문구 몫을 빼고 여유를 둔다.
# 넘치면 잘라내되 잘렸다는 사실을 반드시 알린다 — 조용히 자르면 그 자체가 무성실패이고,
# 에이전트는 잘린 문서를 완전한 것으로 믿는다.
limit=8000
truncated=""
if [ "${#content}" -gt "$limit" ]; then
  content="${content:0:$limit}"
  truncated="

---
IMPORTANT: docs/progress.md가 길어 위 내용은 앞부분만 주입되었다. 전체를 보려면 파일을 직접 읽어라.
그리고 이 파일을 한 페이지로 줄여라 — 길어지면 주입이 잘리고 신호가 묻힌다."
fi

# jq가 실행 중 실패하더라도 훅이 실패 종료코드를 내지 않게 한다.
# 이 저장소의 훅은 워크플로우를 막지 않는 것이 설계 원칙이다 — 상태 주입에 실패했다고
# 세션 시작이 방해받아서는 안 된다.
jq -n --arg ctx "다음은 이 프로젝트의 현재 진행 상태다 (\`docs/progress.md\`). 작업을 시작하기 전에 이것을 기준으로 삼아라.

${content}${truncated}" \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $ctx
    }
  }' || exit 0
exit 0
