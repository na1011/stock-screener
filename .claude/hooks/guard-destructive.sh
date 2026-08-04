#!/bin/bash
# PreToolUse guard: 파괴적인 Bash 명령을 차단하고, Bash를 경유한 시크릿 읽기는 확인을 요구한다.
# 매칭되지 않으면 반드시 exit 0으로 조용히 통과시킨다 (안전망 전용, 워크플로우를 막지 않는다).
set -euo pipefail

# jq가 없으면 판정할 수 없다. 안전망이 워크플로우를 막지 않도록 조용히 통과시킨다.
command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')

if [ -z "$cmd" ]; then
  exit 0
fi

emit() {
  jq -n --arg decision "$1" --arg reason "$2" \
    '{
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: $decision,
        permissionDecisionReason: $reason
      }
    }'
  exit 0
}

# --- 1. 파괴적 명령: deny ---
is_destructive=false

if echo "$cmd" | grep -qE 'rm[[:space:]]+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)([[:space:]]|$)'; then
  is_destructive=true
fi
if echo "$cmd" | grep -qE 'git[[:space:]]+push[[:space:]]+.*(--force|--force-with-lease|-f)([[:space:]]|$)'; then
  is_destructive=true
fi
if echo "$cmd" | grep -qiE '\b(drop[[:space:]]+(table|database|schema)|truncate[[:space:]]+table)\b'; then
  is_destructive=true
fi
if echo "$cmd" | grep -qE 'git[[:space:]]+(reset[[:space:]]+--hard|clean[[:space:]]+-[a-zA-Z]*f)'; then
  is_destructive=true
fi

if [ "$is_destructive" = true ]; then
  emit "deny" "파괴적 명령으로 감지되었습니다. 확인 없이 실행하지 마세요."
fi

# --- 2. Bash 경유 시크릿 읽기: ask ---
# 파일 접근 hook은 Read/Edit/Write만 검사하므로 `cat .env` 같은 우회 경로가 남는다.
# 정상적인 조회일 수 있으므로 차단(deny)이 아닌 확인(ask)으로 둔다.
if echo "$cmd" | grep -qE '(^|[[:space:];|&])(cat|less|more|head|tail|bat|strings|xxd|od|nl|grep|rg|awk|sed|cp|mv|scp|base64)([[:space:]]|$)'; then
  if echo "$cmd" | grep -qE '(\.env([[:space:]]|$|[^.a-zA-Z])|\.env\.[a-zA-Z]|\.pem|\.p12|\.pfx|\.jks|\.keystore|/secrets?/|/credentials/|id_rsa|id_ed25519|\.ssh/)'; then
    # 템플릿·예시 파일만 대상이면 통과시킨다.
    if ! echo "$cmd" | grep -qE '\.env\.(example|sample|template|dist)'; then
      emit "ask" "시크릿 파일을 읽거나 복사하려는 명령으로 보입니다. 실제 시크릿 값이 대화·로그에 남지 않도록 확인 후 진행하세요."
    fi
  fi
fi

exit 0
