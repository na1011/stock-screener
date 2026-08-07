#!/bin/bash
# PreToolUse guard: Read/Edit/Write 대상 경로가 시크릿을 담을 수 있는 파일이면 차단한다.
# 매칭되지 않으면 반드시 exit 0으로 조용히 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
set -euo pipefail

# jq가 없으면 판정할 수 없다. 통과는 시키되 조용히 통과하지는 않는다.
#
# exit 0으로 나가면 "판정했고 시크릿이 아니다"와 "판정하지 못했다"가 같은 신호가 된다.
# 이 가드가 유일한 방어막인 경로들(.env.production, *.key, credentials.json 등 — permissions는
# deny가 allow를 이겨 .env.example 예외를 표현할 수 없으므로 그쪽으로 옮길 수 없다)이
# 그때 흔적 없이 열린다. exit 1은 도구 호출을 그대로 진행시키면서 stderr를 트랜스크립트에
# 남긴다. exit 0의 stderr는 디버그 로그로만 가서 아무도 보지 못한다.
#
# exit 2(차단)를 쓰지 않는 이유는 jq 하나가 없다고 모든 파일 조작이 멈추기 때문이다.
# 차단은 되돌릴 수 없는 것에만 쓴다.
if ! command -v jq >/dev/null 2>&1; then
  echo "guard-secrets: jq가 없어 시크릿 경로를 판정하지 못했다. 이 호출은 검사되지 않은 채 진행된다. jq를 설치하면 복구된다." >&2
  exit 1
fi

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
