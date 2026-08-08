#!/bin/bash
# PostToolUse: 적재 배치가 "아무 일도 하지 않고 성공으로 끝나는" 조합을 알린다.
#
# 이 함정만 hook에 있는 이유는 **트리거가 파일이 아니라 argv의 모양**이기 때문이다.
# `.claude/rules/`의 glob은 파일을 열어야 발화하는데, 적재는 어떤 파일도 열지 않는
# Bash 작업이다. 그래서 규칙 파일에 적어 두면 정작 필요한 순간에 로드되지 않는다.
#
# 차단(deny/ask)이 아니라 알림인 이유: 이 실수는 **되돌릴 수 있다.** 잘못 돌아간
# initial-load는 대상이 0건이라 빠르게 끝나므로 재실행 비용이 거의 없다. 이 저장소는
# 차단을 "되돌릴 수 없는 사고"에만 쓰고(README §hook), `aece427`이 무인 자율 실행을
# 위해 승인 게이트를 걷어냈다. 되돌릴 수 있는 실수에 ask를 붙이면 그 결정을 되돌리는
# 셈이다 — 판단의 시점과 여부는 에이전트에게 남긴다.
set -euo pipefail

command -v jq >/dev/null 2>&1 || exit 0

# shellcheck source=lib/bash-text.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/bash-text.sh"

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')
[ -z "$cmd" ] && exit 0

# heredoc 본문을 걷어낸다. 아래 판정이 따옴표 안을 일부러 보기 때문에, **적재 절차를
# 문서화한 커밋 메시지**가 실제 실행과 구분되지 않았다(2026-08-09 실측). 이 저장소의
# TODO.md·batch-load skill이 바로 그 명령을 문서로 싣는다.
cmd=$(strip_heredoc_bodies "$cmd")

# 인자가 `--args='...'` 따옴표 안에 있으므로 따옴표를 걷어내지 않는다.
# 대신 실제로 gradle을 실행하는 경우로 한정해 단순 언급(`echo "--job=initial-load"`)을 거른다.
echo "$cmd" | grep -qE '(^|[[:space:];|&])(\./)?gradlew?([[:space:]]|$)' || exit 0
case "$cmd" in *bootRun*) ;; *) exit 0 ;; esac
case "$cmd" in *profiles.active=bootstrap*) ;; *) exit 0 ;; esac
case "$cmd" in *--job=initial-load*) ;; *) exit 0 ;; esac
case "$cmd" in *--reload-existing=true*) exit 0 ;; esac

jq -n --arg ctx "이 실행에는 \`--reload-existing=true\`가 없다. 대상 선정이 \`NOT EXISTS (price_daily)\`라 봉이 하나라도 있는 종목은 건너뛰고, **잡은 성공으로 끝난다** — 기간을 늘려도 아무 일이 일어나지 않는다. 새로 적재한 것이 맞는지 행 수로 확인하고, 기존 종목의 구간을 늘리려던 것이면 플래그를 붙여 다시 실행하라." \
  '{
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: $ctx
    }
  }'
exit 0
