#!/bin/bash
# PreToolUse guard: Read/Edit/Write 대상 경로가 시크릿을 담을 수 있는 파일이면 차단한다.
# 매칭되지 않으면 반드시 exit 0으로 조용히 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
set -euo pipefail

# jq가 없으면 판정할 수 없다. 안전망이 워크플로우를 막지 않도록 조용히 통과시킨다.
command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // .tool_input.path // empty')

if [ -z "$path" ]; then
  exit 0
fi

base=$(basename "$path")

is_secret=false

# 1. 파일명 기반 판정
case "$base" in
  # 템플릿·예시 파일은 플레이스홀더만 담으므로 허용한다.
  .env.example|.env.sample|.env.template|.env.dist) is_secret=false ;;
  .env|.env.*) is_secret=true ;;
  *.key|*.pem|*.p12|*.pfx|*.jks|*.keystore) is_secret=true ;;
  id_rsa|id_ed25519|id_ecdsa) is_secret=true ;;
  credentials|credentials.json|service-account*.json) is_secret=true ;;
  # application-{local,secret,prod}* 는 실제 접속 정보를 담기 쉽다.
  application-local.*|application-secret.*|application-prod.*) is_secret=true ;;
esac

# 2. 경로 기반 판정 — basename만으로는 secrets/ 하위 파일을 놓친다.
if [ "$is_secret" = false ]; then
  case "/$path" in
    */secrets/*|*/secret/*|*/credentials/*|*/.ssh/*|*/.gnupg/*) is_secret=true ;;
  esac
fi

if [ "$is_secret" = true ]; then
  jq -n \
    --arg reason "시크릿을 담을 수 있는 경로($path)입니다. 실제 값 대신 .env.example에 플레이스홀더로만 문서화하세요." \
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
