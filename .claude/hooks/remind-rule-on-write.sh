#!/bin/bash
# PostToolUse: Edit·Write로 다룬 파일을 지배하는 `.claude/rules/` 규칙을 포인터로 알린다.
#
# 왜 필요한가 — `paths` 규칙은 매칭 파일을 **Read**할 때 뜬다. 그런데 편집이 언제나 Read를
# 거치지는 않는다: 새 파일 `Write`는 사전 Read를 요구하지 않고, 서브에이전트가 대신 읽은
# 경우에도 본체 세션에는 규칙이 오지 않는다. 그 경로로 고치면 **규칙 없이 고치는 것이고,
# 그 사실은 어디에도 드러나지 않는다.**
#
# `PreToolUse`가 아니라 `PostToolUse`인 이유는 둘이다. 첫째, `additionalContext`는 어느
# 쪽이든 tool result 자리에 꽂히므로 배달 시점이 같다. 둘째, `PostToolUse`는 구조적으로
# 차단이 불가능해 **이 스크립트의 오류가 편집을 막을 수 없다** — `PreToolUse`였다면 문법
# 오류의 종료 코드 2가 그대로 차단이 된다.
#
# 본문이 아니라 포인터를 낸다. 본문은 `paths`가 이미 배달하므로(Read 경로) 여기서 또 밀어
# 넣으면 중복이고, 편집마다 수천 자가 되어 아무것도 안 하는 것보다 나쁘다.
#
# 이미 규칙이 떠 있는 세션에도 한 번은 뜬다 — 훅은 그것을 알 수 없다. 세션당 규칙 파일당
# 1회이므로 최악이 짧은 한 줄이고, **놓치는 쪽이 한 번 더 알리는 쪽보다 나쁘다.**
set -euo pipefail

# jq가 없으면 조용히 통과한다. 부재 경보는 check-doc-staleness.sh가 세션 시작에 한 번 낸다.
command -v jq >/dev/null 2>&1 || exit 0

input=$(cat)
path=$(echo "$input" | jq -r '.tool_input.file_path // empty')
session=$(echo "$input" | jq -r '.session_id // "nosession"')
# 서브에이전트는 부모와 session_id가 같다. 키를 나누지 않으면 한쪽이 마커를 태우고 다른
# 쪽이 영영 못 받는다.
agent=$(echo "$input" | jq -r '.agent_id // "main"')
[ -z "$path" ] && exit 0

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
rules_dir="${root}/.claude/rules"
[ -d "$rules_dir" ] || exit 0

# 저장소 상대 경로로 정규화한다. 전역 사본(`~/.claude/**`)도 같은 glob으로 판정한다 —
# `harness-hooks.md`가 저장소 사본과 전역 사본을 함께 고치라고 요구하기 때문이다.
rel="${path#"${root}/"}"
rel="${rel#./}"
case "$rel" in
  "$HOME"/.claude/*) rel=".claude/${rel#"$HOME"/.claude/}" ;;
esac

marker_dir="${TMPDIR:-/tmp}/claude-harness-hooks/${session}-${agent}/remind-rule-on-write"

already_fired() {
  local key marker
  key=$(echo "$1" | shasum 2>/dev/null | cut -d' ' -f1 || echo "")
  [ -z "$key" ] && return 1
  marker="${marker_dir}/${key}"
  mkdir -p "$marker_dir" 2>/dev/null || return 1
  [ -f "$marker" ] && return 0
  touch "$marker" 2>/dev/null || true
  return 1
}

# `**`를 `*`로 접는다. bash `[[ ]]`에서 `*`는 `/`를 넘는다. **이 접기는 bash 전용이다** —
# zsh에서 같은 대조를 하면 0건이 나온다.
matched=""
for rule in "$rules_dir"/*.md; do
  [ -f "$rule" ] || continue
  base=$(basename "$rule")
  globs=$(awk '
    /^paths:[[:space:]]*$/ { f = 1; next }
    f && /^[[:space:]]*-[[:space:]]*/ {
      sub(/^[[:space:]]*-[[:space:]]*/, "")
      gsub(/^["'"'"']|["'"'"']$/, "")
      gsub(/\*\*/, "*")
      if ($0 != "" && $0 != "-" && $0 != "--") print
      next
    }
    f { exit }
  ' "$rule" 2>/dev/null || true)
  [ -z "$globs" ] && continue
  while IFS= read -r g; do
    [ -z "$g" ] && continue
    # shellcheck disable=SC2053  # 우변을 따옴표로 감싸면 glob이 아니라 리터럴이 된다
    if [[ "$rel" == $g ]]; then
      matched="${matched}${base} "
      break
    fi
  done <<< "$globs"
done

[ -z "$matched" ] && exit 0

fresh=""
for rname in $matched; do
  already_fired "${rname}|write-touch" && continue
  fresh="${fresh}\`.claude/rules/${rname}\` "
done
[ -z "${fresh// /}" ] && exit 0

jq -n --arg ctx "\`${rel}\`를 고쳤다. 이 파일을 지배하는 규칙은 ${fresh}다. **이미 컨텍스트에 있으면 무시하고, 없으면 지금 Read로 연다** — \`paths\` 규칙은 파일을 Read할 때만 뜨므로 읽지 않고 고친 경우에는 오지 않았다." \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
