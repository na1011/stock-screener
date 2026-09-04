#!/bin/bash
# PreToolUse: **순위 축이 오염된 채로 리밸런싱을 돌리는 것**을 막는다.
#
# **MUST: 이 가드가 덮는 것은 두 갈래 중 하나뿐이라는 것을 알고 쓴다.** 리밸런싱은
# `PipelineScheduler:168`의 `@Scheduled(cron = "0 0 20 * * SAT")`가 앱 프로세스 **안에서
# 직접** 잡을 부르는 갈래로도 돈다 — 그 갈래는 Bash를 거치지 않으므로 **이 훅이 원리적으로
# 발화하지 않는다.** 여기서 막는 것은 **에이전트가 손으로 돌리는 것**이고, 2026-09-04의
# 사고가 정확히 그 경로였다(`universe-rebalance` 실행 이력 전량이 `triggered_by = MANUAL`).
# **MUST NOT: 이 가드가 있으니 오염이 유니버스에 안 닿는다고 적지 않는다** — 자동 갈래는
# ADR-0125의 `premise` 펜스가 세션 시작에 신고할 뿐 막지 않는다.
#
# 막는 대상이 `--job=universe` 하나인 것이 의도다. 이 저장소에서 되돌릴 수 없는 적재는
# 그것뿐이다 — `universe_membership`의 편입 이력은 **사후 복원이 안 되고**(ADR-0086·0099)
# 같은 날 두 번 돌리면 `ck_membership_period`의 0일 구간 금지에 걸려 **실행이 통째로
# 되돌아간다.** 즉 잘못 돌리면 고치는 데 하루가 걸리고, 그 사이 잘못된 편입이 화면과
# 이력에 남는다. `initial-load`·`financial-refill`은 멱등하거나 덤프로 돌아오므로
# 여기서 막지 않는다(후자는 `guard-rebuild-without-dump.sh`가 본다).
#
# **왜 알림이 아니라 차단인가** — `harness-hooks.md`가 못박듯 `PreToolUse`의
# `additionalContext`도 **도구가 실행된 뒤에** 읽힌다. 실행 전에 무언가를 알리는 길은
# `deny`뿐이고, 여기서 정말 되돌릴 수 없는 것은 실행 자체가 아니라 **오염된 입력 위에서
# 돌리는 것**이다.
#
# **왜 이 검사가 필요한가** — 「먼저 재라」는 문장은 이미 여러 곳에 있고, 그런데도
# 2026-09-04에 그 문장을 읽은 채로 안 재고 돌렸다. 계획서에 *"AKTX·TBLA가 조 단위로
# 잡혀 있다 — 같은 파이프라인의 기존 오염이고 **이번 범위 밖**"*이라고 **적어 놓고**
# 리밸런싱을 돌렸고, 그 오염된 시총이 둘을 상위 10위 안에 편입시켰다. 잘못 편입된 종목
# 하나는 정상 종목 하나를 1,000위 밖으로 민다. **판단을 바꾸는 것은 지시가 아니라 그
# 자리에 있는 수다.**
#
# **탈출구는 오염을 고치는 것이다.** 이상치가 0이면 이 가드는 조용하다 —
# `guard-rebuild-without-dump.sh`가 "덤프가 하나라도 있으면 다시 안 걸린다"로 여는 것과
# 같은 모양이고, 시간 규칙이나 승인 플래그처럼 **선택지를 줄이는 형태를 쓰지 않는** 이유도
# 같다(`harness-core.md`).
set -euo pipefail

# fail-open. 다만 **판정하지 못한 것은 드러낸다** — 침묵은 "판정했고 문제없다"와 같은
# 신호이고, 이 저장소에서 그 구분이 사라지는 것이 가장 나쁜 실패다(`harness-hooks.md`).
command -v jq >/dev/null 2>&1 || exit 0

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.claude/hooks/lib/bash-text.sh
. "$HERE/lib/bash-text.sh"

input=$(cat)
cmd=$(echo "$input" | jq -r '.tool_input.command // empty')
[ -z "$cmd" ] && exit 0

# 커밋 메시지·문서에 명령을 적는 것만으로 막히지 않게 한다. 이 저장소는 적재 절차를
# 문서로 싣고(TODO.md·batch-load skill) 실제로 그 때문에 deny가 난 전례가 있다.
cmd=$(strip_heredoc_bodies "$cmd")
echo "$cmd" | grep -qE '(^|[[:space:];|&])(\./)?gradlew?([[:space:]]|$)' || exit 0
case "$cmd" in
  *--job=universe*) ;;
  *) exit 0 ;;
esac

if ! command -v psql >/dev/null 2>&1; then
  echo "[guard-rebalance] psql이 없어 순위 축을 재지 못했다. 판정하지 못했다." >&2
  exit 1
fi

export PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5s'
export PGCONNECT_TIMEOUT=3

# 시총 = 주식수 × 종가이고, 주식수는 SEC EDGAR가 준 값을 그대로 담는다. 그 값이 제출자
# 오류(dei: 태그가 1e6 배)나 개념 혼동(폴백이 가중평균 주식수를 집는다)으로 조 단위가
# 되는 일이 있다 — 2026-09-04 탐침이 소스에서 확정했다.
#
# **절대 상한이 아니라 종목 안 중앙값 대비로 재는 이유**: 상한은 임의값이라 시장이 커지면
# 낡는다. 종목 안에서 최신 값이 자기 이력의 50배를 넘는 것은 분할로 설명되지 않는다 —
# 분할은 원장(`corporate_action`)이 따로 잡고 주식수 계열에는 그 배수가 안 실린다.
outliers=$(psql -d "${DB_NAME:-stock_screener}" -At -F'|' -c "
WITH med AS (
  SELECT stock_id, (percentile_cont(0.5) WITHIN GROUP (ORDER BY shares_outstanding))::numeric AS md
  FROM financial_statement WHERE shares_outstanding > 0 GROUP BY 1 HAVING count(*) >= 4),
cur AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding AS cur
  FROM financial_statement f WHERE f.shares_outstanding > 0 AND f.is_latest
  ORDER BY f.stock_id, f.period_end DESC)
SELECT string_agg(t, ', ' ORDER BY t) FROM (
  SELECT s.ticker || ' ' || round(c.cur / med.md, 0) || '배' AS t, c.cur / med.md AS x
  FROM cur c JOIN med ON med.stock_id = c.stock_id JOIN stock s ON s.id = c.stock_id
  WHERE c.cur / med.md > 50 ORDER BY 2 DESC LIMIT 10) z" 2>/dev/null) || {
  echo "[guard-rebalance] 순위 축 질의가 실패했다. 판정하지 못했다." >&2
  exit 1
}

[ -z "$outliers" ] && exit 0

jq -n --arg list "$outliers" \
  '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: ("순위 축에 이상치가 있다 — 최신 상장주식수가 자기 이력 중앙값의 50배를 넘는 종목: " + $list + ". 시총 = 주식수 × 종가이므로 이 값들이 상위 N을 밀어낸다. 잘못 편입된 종목 하나가 정상 종목 하나를 1,000위 밖으로 밀고, `universe_membership`의 편입 이력은 사후 복원이 안 된다(ADR-0086·0099). 게다가 같은 날 두 번 못 돌아 고치는 데 하루가 걸린다. **주식수를 먼저 고쳐라** — 이상치가 0이 되면 이 가드는 다시 걸리지 않는다. 오염을 알면서 돌려야 하는 사정이 있으면 사용자에게 그 수를 보이고 승인을 받아라.")
    }
  }'
exit 0
