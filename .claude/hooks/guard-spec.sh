#!/bin/bash
# PreToolUse guard: 기획 정본 문서 수정 시 확인을 요구한다 (완전 차단이 아닌 ask).
# 매칭되지 않으면 반드시 exit 0으로 조용히 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
set -euo pipefail

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')

if [ -z "$path" ]; then
  exit 0
fi

if [[ "$path" == *"docs/stock-screener-project-spec.md" ]]; then
  jq -n \
    --arg reason "기획 정본 문서(stock-screener-project-spec.md)입니다. 임의 수정 대신 변경을 제안하세요. 정말 갱신이 필요하면 승인 후 진행하세요." \
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
