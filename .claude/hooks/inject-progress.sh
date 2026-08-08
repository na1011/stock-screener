#!/bin/bash
# SessionStart: 진행 상태(docs/progress.md)를 세션 시작 시 주입한다.
#
# 저장소에 파일이 있는 것만으로는 부족하다. "다음 세션이 알아서 찾아 읽겠지"에 기대면
# 실제로는 읽지 않는다 — 새 세션은 무엇을 모르는지조차 모르기 때문이다.
# additionalContext는 첫 프롬프트 이전에 주입되므로 확실하다.
#
# 차단하지 않는다. 다만 **조용히 실패하지도 않는다** — 주입이 죽으면 다음 세션이 눈먼 채
# 시작하는데, 그것이 이 훅이 막으려던 바로 그 상태다. 실패는 평문 stdout으로 알린다
# (`SessionStart`의 평문 stdout은 그대로 컨텍스트가 되고 jq가 필요 없다).
# 수단을 이벤트별로 가르는 근거는 `.claude/rules/harness.md`에 있다.
set -euo pipefail

# 실패를 알리고 나간다. 종료코드가 아니라 이 문구가 신호다.
bail() {
  echo "진행 상태 주입에 실패했다: $1 다음 세션은 docs/progress.md를 직접 읽어야 한다."
  exit 0
}

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
cd "$root" 2>/dev/null || bail "프로젝트 루트($root)로 이동할 수 없다."

progress="docs/progress.md"
[ -f "$progress" ] || bail "$progress 파일이 없다."

content=$(cat "$progress" 2>/dev/null) || bail "$progress를 읽지 못했다."
[ -n "$content" ] || bail "$progress가 비어 있다."

# jq는 아래 출력에만 필요하다. 없으면 내용을 그대로 stdout으로 낸다 —
# 잘려서라도 컨텍스트에 닿는 것이 아무것도 안 하는 것보다 낫다.
if ! command -v jq >/dev/null 2>&1; then
  echo "jq가 없어 구조화 주입을 건너뛴다. 아래가 docs/progress.md 원문이다."
  echo
  echo "$content"
  exit 0
fi

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

jq -n --arg ctx "다음은 이 프로젝트의 현재 진행 상태다 (\`docs/progress.md\`). 작업을 시작하기 전에 이것을 기준으로 삼아라.

${content}${truncated}" \
  '{
    hookSpecificOutput: {
      hookEventName: "SessionStart",
      additionalContext: $ctx
    }
  }' || bail "jq가 주입 페이로드를 만들지 못했다."
exit 0
