#!/bin/bash
# PreToolUse: 덤프가 하나도 없는데 수정계수 재구축을 띄우려 하면 막는다(ADR-0049).
#
# 트리거가 파일이 아니라 **argv의 모양**이라 `.claude/rules/`의 glob으로는 발화하지 않고,
# `PostToolUse` 알림은 **명령이 실행된 뒤**에 뜬다. 차단이 필요하면 여기뿐이다.
#
# **막는 범위가 좁은 것이 의도다 — "덤프가 하나도 없을 때"만이다.**
# ADR-0049가 요구하는 것은 *"첫 실행 전에 pg_dump를 뜬다"*이고, 신선도 창(예: 6시간)을
# 걸면 정상 재구축과 **되돌리기 리허설까지** 함께 막힌다. 재구축은 원장에서 매번 다시
# 짓는 멱등 연산이고 되돌리기가 `judge` + 재실행으로 성립하므로(ADR-0049 §쉬워지는 것),
# 그것을 시간 규칙으로 막는 것은 `harness-core.md`가 금지하는 "선택지를 줄이는 기제"다.
# 좁게 막으면 실제로 되돌릴 수 없는 **첫 호출 하나**에만 걸린다.
#
# `guard-destructive.sh`에 넣지 않은 이유는 그 파일이 `~/.claude/hooks/`에 사본으로도
# 등록돼 있어 고치면 전역까지 함께 고쳐야 하는데(`harness-hooks.md`), 이 판정은
# `$DUMP_DIR`와 `--job=adjustment-rebuild`를 아는 **이 저장소 전용**이기 때문이다.
set -euo pipefail

# jq가 없으면 판정할 수 없다. 중앙 보고자(check-doc-staleness.sh)가 의존성 부재를 세므로
# 여기서는 조용히 통과한다 — 매 호출 stderr를 내면 트랜스크립트가 그것만으로 찬다.
command -v jq >/dev/null 2>&1 || exit 0

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.claude/hooks/lib/bash-text.sh
. "$HERE/lib/bash-text.sh"

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')
[ -z "$cmd" ] && exit 0

# 커밋 메시지·문서에 이 명령을 적는 것만으로 차단되지 않게 한다. 이 저장소는 적재 절차를
# 문서로 싣고(TODO.md·batch-load skill) 실제로 그 때문에 deny가 난 전례가 있다.
cmd=$(strip_heredoc_bodies "$cmd")

# 실제로 gradle을 타는 경우로 한정한다. 인자가 `--args='...'` 따옴표 안이라 따옴표는 걷지 않는다.
echo "$cmd" | grep -qE '(^|[[:space:];|&])(\./)?gradlew?([[:space:]]|$)' || exit 0
case "$cmd" in *--job=adjustment-rebuild*) ;; *) exit 0 ;; esac

# 경로의 정본은 ops/lib.sh다. 여기서 다시 쓰면 한쪽만 고쳐지는 날이 온다.
lib="$HERE/../../ops/lib.sh"
if [ ! -f "$lib" ]; then
  # 판정하지 못했다는 것을 드러낸다. exit 0 + 침묵은 "판정했고 문제없다"와 같은 신호다.
  echo "[guard-rebuild] ops/lib.sh가 없어 덤프 경로를 알 수 없다. 판정하지 못했다." >&2
  exit 1
fi
# shellcheck source=ops/lib.sh
. "$lib"

if compgen -G "$DUMP_DIR/*.dump" >/dev/null 2>&1; then
  exit 0
fi

jq -n --arg reason "\`$DUMP_DIR\`에 덤프가 하나도 없다. \`rebuild()\`의 첫 호출은 저장된 \`adj_close\`를 전 구간 소급해 다시 쓰고 그 종목의 \`indicator_value\`를 통째로 지운다(ADR-0049). **먼저 \`ops/dump-db.sh\`를 돌려라** — 33MB·3초다. 덤프가 하나라도 있으면 이 가드는 다시 걸리지 않는다(정상 재구축과 되돌리기 리허설을 막지 않으려는 것이다)." \
  '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: $reason
    }
  }'
exit 0
