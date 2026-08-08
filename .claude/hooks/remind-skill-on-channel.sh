#!/bin/bash
# PostToolUse: 파일이 아닌 **채널**에 진입하면 그 채널의 skill을 알린다.
#
# 왜 필요한가 — skill은 pull이고, 그 포인터는 200 메시지 전에 읽은 문서 안에 있다.
# `.claude/rules/harness.md`가 "pull 기제는 세션이 길어질수록 떨어지는 준수율에 종속되는데,
# 제약이 가장 필요한 시점이 바로 그 약한 시점"이라고 이미 경고한다.
#
# 2026-08-09 실측이 그 실증이다: 한 세션이 문서가 가리킨 브라우저 skill을
# **"읽겠다"고 쓰고 로드하지 않았다.** 선언조차 아무도 확인하지 않았다.
#
# 그런데 브라우저 작업에는 **기계적 진입점이 있다** — MCP 도구 호출. 파일을 열지 않고 일어나는
# 일이라 `paths` glob으로는 원리적으로 표현할 수 없고, argv도 아니라 Bash 훅으로도 안 잡힌다.
# 이 훅이 메우는 것은 그 트리거 어휘의 공백이다.
#
# 진입 계열만 좁히지 않고 **첫 호출이 될 수 있는 것을 모두** matcher에 넣는다.
# `navigate`는 단독 호출 시 `tabs_context_mcp`를 도구 내부에서 부르므로 에이전트의 도구
# 호출로는 남지 않고, 브라우저가 하나면 `list`·`select`도 건너뛴다. 빈도는 중복 억제로 잡는다 —
# 세션당 1회면 호출이 40번이든 한 줄이다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
tool=$(echo "$input" | jq -r '.tool_name // empty')
session=$(echo "$input" | jq -r '.session_id // "nosession"')
[ -z "$tool" ] && exit 0

# ── 채널 → skill 표 ────────────────────────────────────────────────────────
# `도구 이름 접두어|skill 이름|한 줄 사유`. MCP 계열이 늘면 한 줄 추가로 끝난다.
# **사유는 받침 있는 말로 끝낸다** — 아래 템플릿이 조사 `은`을 붙인다. 안 지키면 `자리은`이 나간다.
# **없는 계열을 미리 적지 않는다** — 적어 두면 그것이 곧 낡은 목록이 되고, 낡은 목록은
# "덮여 있다"고 믿게 만든다.
channels="mcp__claude-in-chrome__|browser-driving|지금 화면이 어느 빌드인지·되돌릴 수 없는 조작·쿼터를 태우는 버튼·브라우저 도구가 거짓 음성을 내는 곳"

skill=""
reason=""
while IFS='|' read -r prefix sname sreason; do
  [ -z "$prefix" ] && continue
  case "$tool" in
    "${prefix}"*) skill="$sname"; reason="$sreason"; break ;;
  esac
done <<< "$channels"

[ -z "$skill" ] && exit 0

marker_dir="${TMPDIR:-/tmp}/claude-harness-hooks/${session}/remind-skill-on-channel"
key=$(echo "$skill" | shasum 2>/dev/null | cut -d' ' -f1 || echo "")
if [ -n "$key" ]; then
  # 마커를 만들 수 없으면 중복 억제를 포기하고 알린다 — 알림 훅은 발화 쪽으로 실패한다.
  if mkdir -p "$marker_dir" 2>/dev/null; then
    [ -f "${marker_dir}/${key}" ] && exit 0
    touch "${marker_dir}/${key}" 2>/dev/null || true
  fi
fi

jq -n --arg ctx "브라우저 채널에 진입했다. ${reason}은 \`${skill}\` skill에 있다 — Skill 도구로 로드하라. 이 알림이 있는 이유는 포인터를 읽고도 로드하지 않은 전례가 있어서다." \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
