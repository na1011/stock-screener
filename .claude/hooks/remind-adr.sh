#!/bin/bash
# PostToolUse guard: 되돌리기 어려운 결정이 담기기 쉬운 파일이 편집되면 ADR 기록을 상기시킨다.
# 차단하지 않는다 (PostToolUse는 구조적으로 차단 불가). 순수 알림이다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
session=$(echo "$input" | jq -r '.session_id // "nosession"')

[ -z "$path" ] && exit 0

base=$(basename "$path")

# ADR 기록 대상 — 새 의존성 / 저장 구조 / 외부 연동 / 인프라 구성
category=""
case "$base" in
  build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|libs.versions.toml)
    category="의존성" ;;
  application.yaml|application.yml|application-*.yaml|application-*.yml)
    category="외부 연동·저장 구조" ;;
  Dockerfile|Dockerfile.*|compose.yaml|compose.yml|docker-compose.yaml|docker-compose.yml)
    category="인프라 구성" ;;
  *.tf|*.tfvars)
    category="인프라 구성" ;;
  V[0-9]*__*.sql|*.changelog.xml)
    category="저장 구조(마이그레이션)" ;;
esac

[ -z "$category" ] && exit 0

# 세션 단위 중복 억제 — 같은 파일·카테고리에 대해 한 세션에 한 번만 발화한다.
# 저장소 전역 ADR 개수로 억제하지 않는다: 그렇게 하면 첫 ADR이 생긴 뒤 영구히 침묵해,
# 정작 기록할 결정이 쌓이기 시작하는 시점부터 리마인더가 죽는다.
# 저장소를 오염시키지 않도록 프로젝트 밖 임시 경로를 쓴다.
marker_dir="${TMPDIR:-/tmp}/claude-adr-reminder/${session}"
# shasum이 없으면 중복 억제만 포기하고 알림은 계속한다 (순수 알림이므로 발화가 우선).
key=$(echo "${path}|${category}" | shasum 2>/dev/null | cut -d' ' -f1 || echo "")
if [ -n "$key" ]; then
  marker="${marker_dir}/${key}"
  mkdir -p "$marker_dir" 2>/dev/null || exit 0
  [ -f "$marker" ] && exit 0
  touch "$marker" 2>/dev/null || true
fi

jq -n --arg ctx "\`${base}\` 가 변경되었다 (${category}). 이 변경이 되돌리기 어려운 결정을 담고 있다면 \`docs/adr/\`에 \`0000-template.md\` 형식으로 기록하라. 단순 버전 조정이나 포맷 변경이면 기록하지 않아도 된다. 판단은 직접 하라 — 허가를 구하지 말고 결정한 뒤 기록하라." \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
