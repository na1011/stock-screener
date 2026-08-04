#!/bin/bash
# PreToolUse guard: 파괴적인 Bash 명령을 차단한다.
# 매칭되지 않으면 반드시 exit 0으로 조용히 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
set -euo pipefail

input=$(cat)
command=$(echo "$input" | jq -r '.tool_input.command // empty')

if [ -z "$command" ]; then
  exit 0
fi

is_destructive=false

if echo "$command" | grep -qE 'rm[[:space:]]+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)([[:space:]]|$)'; then
  is_destructive=true
fi
if echo "$command" | grep -qE 'git[[:space:]]+push[[:space:]]+.*(--force|-f)([[:space:]]|$)'; then
  is_destructive=true
fi
if echo "$command" | grep -qiE '\b(drop[[:space:]]+(table|database|schema)|truncate[[:space:]]+table)\b'; then
  is_destructive=true
fi
if echo "$command" | grep -qE 'git[[:space:]]+(reset[[:space:]]+--hard|clean[[:space:]]+-[a-zA-Z]*f)'; then
  is_destructive=true
fi

if [ "$is_destructive" = true ]; then
  jq -n \
    --arg reason "파괴적 명령으로 감지되었습니다. 확인 없이 실행하지 마세요." \
    '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason: $reason
      }
    }'
  exit 0
fi

exit 0
