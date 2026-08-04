#!/bin/bash
# PreToolUse guard: Read/Edit/Write 대상 경로가 시크릿을 담을 수 있는 파일이면 차단한다.
# 매칭되지 않으면 반드시 exit 0으로 조용히 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
set -euo pipefail

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // .tool_input.path // empty')

if [ -z "$path" ]; then
  exit 0
fi

base=$(basename "$path")

is_secret=false
case "$base" in
  .env|.env.*) is_secret=true ;;
  *.key|*.pem|*.p12|*.pfx) is_secret=true ;;
  application-local.yaml|application-local.yml|application-secret.yaml|application-secret.yml) is_secret=true ;;
esac

if [ "$is_secret" = true ]; then
  jq -n \
    --arg reason "시크릿을 담을 수 있는 파일($base)입니다. .env.example에 플레이스홀더로만 문서화하세요." \
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
