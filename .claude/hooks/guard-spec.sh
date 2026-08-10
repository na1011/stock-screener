#!/bin/bash
# PreToolUse guard: 기획 정본 문서 수정 시 확인을 요구한다 (완전 차단이 아닌 ask).
# 매칭되지 않으면 exit 0으로 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
# 단 그것은 **판정한 뒤**의 이야기다 — 판정 자체를 못 했으면 조용히 나가지 않는다.
set -euo pipefail

# jq가 없으면 판정할 수 없다. 안전망이 워크플로우를 막지 않도록 조용히 통과시킨다.
command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

if [ -z "$path" ]; then
  exit 0
fi

# 사람이 쓴 정본은 둘이다. 층은 다르지만(충돌 시 spec이 이긴다 — ADR-0041) 에이전트가
# 임의로 고치면 안 된다는 점에서는 같으므로 같은 게이트를 건다. 둘째 것을 뺐다가 실제로
# 사본이 하나 더 생긴 적이 있다.
if [[ "$path" == *"docs/stock-screener-project-spec.md" \
   || "$path" == *"docs/external/screener-spec-midterm-swing.md" ]]; then
  jq -n \
    --arg reason "사람이 쓴 정본 문서($(basename "$path"))입니다. 임의 수정 대신 변경을 제안하세요. 정말 갱신이 필요하면 승인 후 진행하고, 그 경위를 ADR에 남기세요." \
    '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "ask",
        permissionDecisionReason: $reason
      }
    }'
  exit 0
fi

exit 0
