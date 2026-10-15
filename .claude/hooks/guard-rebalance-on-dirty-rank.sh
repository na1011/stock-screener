#!/bin/bash
# PreToolUse: **순위 축이 오염된 채로 리밸런싱을 돌리는 것**을 막는다.
#
# **MUST: 이 가드가 덮는 것은 두 갈래 중 하나뿐이라는 것을 알고 쓴다.** 리밸런싱은
# `PipelineScheduler:168`의 `@Scheduled(cron = "0 0 20 * * SAT")`가 앱 프로세스 **안에서
# 직접** 잡을 부르는 갈래로도 돈다 — 그 갈래는 Bash를 거치지 않으므로 **이 훅이 원리적으로
# 발화하지 않는다.** 여기서 막는 것은 **에이전트가 손으로 돌리는 것**이고, 2026-09-04의
# 사고가 정확히 그 경로였다(2026-09-04 09:48 · `triggered_by = MANUAL`). **MUST NOT: 「실행
# 이력 전량이 MANUAL」로 적지 마라 — 거짓이다**(실측 MANUAL 9 · SCHEDULER 3 · ADMIN 1).
# 토요일 크론은 3/3 발화했다 — 자동 갈래는 드문 길이 아니라 **매주 도는 길**이다.
# **MUST NOT: 이 가드가 있으니 오염이 유니버스에 안 닿는다고 적지 않는다** — 자동 갈래는
# ADR-0125의 `premise` 펜스가 세션 시작에 신고할 뿐 막지 않는다.
#
# 막는 대상이 `--job=universe*` 인 것이 의도다(`universe-restore`도 부분 문자열로 걸린다).
# **MUST NOT: 그 근거를 「편입 이력은 사후 복원이 안 된다」로 적지 않는다 — 2026-09-10에
# 거짓으로 판명됐다**(ADR-0135가 보존 창을 재현율 KR 0.995 · US 0.987로 복원했다).
#
# 남는 근거는 셋이고 전부 여전히 참이다. ① **같은 날 두 번 돌리면** `ck_membership_period`의
# 0일 구간 금지에 걸려 **실행이 통째로 되돌아간다** — 고치는 데 하루가 걸린다. ② 그 사이
# **잘못된 편입이 화면과 백분위 모집단에 실린다**(모집단이 그날 편입이라 과거 날짜까지 간다).
# ③ 되돌리려면 **소스를 다시 불러야 하고** 그것은 콜이자 재현율 1이 아닌 재구성이다.
# `initial-load`·`financial-refill`은 멱등하거나 덤프로 돌아오므로 여기서 막지 않는다
# (후자는 `guard-rebuild-without-dump.sh`가 본다).
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
# **판정축은 「그 행 안에서 두 값이 맞는가」다 — 종목 자기 이력과 비교하지 않는다.**
# 주식수와 `net_income / eps`는 같은 보고서의 서로 다른 칸이라, 한쪽이 축척을 잘못 받으면
# 둘이 갈린다. 이력을 안 보므로 **이력 전체가 오염된 종목도 잡히고**(자기 참조 축은 그때
# 최신/중앙값이 1에 붙어 아무것도 못 본다) **정당한 희석은 안 잡힌다**(두 칸이 함께 커진다).
#
# **옛 축(최신 vs 이력 중앙값 50배)을 되돌리지 마라.** 실측(2026-09-08)으로 그 축은
# **거짓 양성 4/4 · 거짓 음성 7/7**이었다 — 신고하던 `DVLT`·`EMAT`·`ORBS`·`QXO`는 전부 실제
# 희석이고(`DVLT` 4.7M→950M이 매끄럽다), 정작 위험한 일곱은 배수가 0.00~1.21이라 안 걸렸다.
# 그리고 그 축은 **ADR-0125 §버린 대안이 「분포에 절단점이 없다」로 이미 기각한 것**이다
# (실측 분포: 10배 아래 4,533 → 25 → 4 → 9 → 3 → 3 → 1로 임계 50 아래위에 3+3이 붙어 있다).
#
# 이 축의 분포는 갈린다 — ±2배 안에 **4,203**, 애매 구간 [20,100)에 4, 위험 [100,∞)에 7이고
# **편입 1,000 중 위험은 0**이다.
# 미국 관문(V70) 뒤에는 순위가 클래스 축 판정을 입힌 주식수를 읽는다(JdbcStoredShareCounts) — 이 축도 그 값으로 잰다.
# 판정 표가 있는가로 가른다(마이그레이션은 관문 직전에만 들어온다). ADR-0125 전제와 같은 분기다.
src="financial_statement f"; sh="f.shares_outstanding"
if [ "$(psql -d "${DB_NAME:-stock_screener}" -At -c "SELECT to_regclass('public.us_share_class_effective') IS NOT NULL" 2>/dev/null)" = "t" ]; then
  src="financial_statement f LEFT JOIN us_share_class_effective e ON e.financial_statement_id = f.id"
  sh="CASE WHEN e.financial_statement_id IS NULL THEN f.shares_outstanding ELSE e.shares END"
fi
outliers=$(psql -d "${DB_NAME:-stock_screener}" -At -F'|' -c "
WITH cur AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, $sh AS sh,
         COALESCE(f.net_income_controlling, f.net_income) / NULLIF(f.eps, 0) AS imp
  FROM $src WHERE $sh > 0
  ORDER BY f.stock_id, f.period_end DESC, f.revision_no DESC)
SELECT string_agg(t, ', ' ORDER BY t) FROM (
  SELECT s.ticker || ' ' || round(c.imp / c.sh, 0) || '배' AS t, c.imp / c.sh AS x
  FROM cur c JOIN stock s ON s.id = c.stock_id
  WHERE c.imp IS NOT NULL AND c.imp / c.sh >= 100 AND c.imp / c.sh < 10000
  ORDER BY 2 DESC LIMIT 10) z" 2>/dev/null) || {
  echo "[guard-rebalance] 순위 축 질의가 실패했다. 판정하지 못했다." >&2
  exit 1
}

# **MUST: 상한 10,000을 지우지 마라.** `eps`가 0에 가까우면 함의값이 터진다 — 실측으로
# `DYAI` 277억주 · `KXIN` 2,118억주다. 그 둘을 신고하면 가드가 영구히 막힌다.
#
# **MUST NOT: 「eps가 없어 판정 못 한 종목」으로 막지 않는다.** 편입 1,000 중 65가 그렇다.
# 못 잰 것은 오염의 증거가 아니고, 그것으로 막으면 이 가드가 다시 도달 불가능한 상태가 된다.
# 다만 그 수를 함께 보인다 — 침묵은 「판정했고 문제없다」와 같은 신호다.
unjudged=$(psql -d "${DB_NAME:-stock_screener}" -At -c "
SELECT count(*) FROM (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.eps
  FROM $src
  JOIN universe_membership m ON m.stock_id = f.stock_id AND m.effective_to IS NULL
  JOIN universe u ON u.code = m.universe_code AND u.is_active
  WHERE $sh > 0
  ORDER BY f.stock_id, f.period_end DESC, f.revision_no DESC) t
WHERE t.eps IS NULL OR t.eps = 0" 2>/dev/null || echo "?")

[ -z "$outliers" ] && exit 0

jq -n --arg list "$outliers" --arg unjudged "$unjudged" \
  '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: ("순위 축에 오염이 있다 — 저장된 상장주식수가 같은 보고서의 `net_income / eps`가 함의하는 수와 100배 이상 갈리는 종목: " + $list + ". 시총 = 주식수 × 종가이므로 그 종목은 순위에서 **밀려나거나 밀어낸다**. 잘못 돌리면 같은 날 다시 못 돌리고(0일 구간 금지) 다음 리밸런싱까지 화면과 백분위 모집단에 남는다. **그 행들을 먼저 고쳐라** — 근거 둘을 세우는 절차는 ADR-0126 §규칙이고, 이력 전체가 오염된 종목은 세 번째 근거로 `SecEdgarSharesProbeLiveTest.probeCrossTagSpreadWithinOneFiling`을 쓴다. 이 축은 **0에 도달할 수 있다**(옛 축은 정당한 희석을 영구히 신고했다). 참고로 `eps`가 없어 판정하지 못한 편입 종목이 " + $unjudged + "개다 — 막지는 않지만 그만큼은 이 가드가 못 본다. 오염을 알면서 돌려야 하면 사용자에게 이 수를 보이고 승인을 받아라.")
    }
  }'
exit 0
