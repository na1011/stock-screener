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

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# 이 세션에 이미 새 ADR이 작성되었다면 상기시킬 필요가 없다.
adr_count=$(find "$root/docs/adr" -maxdepth 1 -name 'ADR-[0-9][0-9][0-9][0-9]-*.md' \
              2>/dev/null | wc -l | tr -d ' ')
[ "${adr_count:-0}" -gt 0 ] && exit 0

# 세션 단위 중복 억제 — 같은 파일에 대해 한 세션에 한 번만 발화한다.
# 저장소를 오염시키지 않도록 프로젝트 밖 임시 경로를 쓴다.
marker_dir="${TMPDIR:-/tmp}/claude-adr-reminder/${session}"
marker="${marker_dir}/$(echo "$path" | shasum | cut -d' ' -f1)"
mkdir -p "$marker_dir" 2>/dev/null || exit 0
[ -f "$marker" ] && exit 0
touch "$marker" 2>/dev/null || true

jq -n --arg ctx "\`${base}\` 가 변경되었다 (${category}). 이 변경이 되돌리기 어려운 결정을 담고 있다면 \`docs/adr/\`에 \`0000-template.md\` 형식으로 기록하라. 단순 버전 조정이나 포맷 변경이면 기록하지 않아도 된다. 판단은 직접 하라 — 허가를 구하지 말고 결정한 뒤 기록하라." \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
