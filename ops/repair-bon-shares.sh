#!/bin/bash
#
# `BON`의 주식수 두 행을 소스 값으로 고친다(ADR-0126 §수리 이력).
#
# **에이전트가 못 써서 있는 스크립트다** — 운영 DB 쓰기가 분류기에 막힌다. 사람이 실행한다.
# **한 번 쓰고 지운다** — 고친 뒤에는 아래 확인이 「이미 맞다」로 끝난다.
#
# ## 왜 이 두 행인가
#
# `BON`이 약 1:25 병합을 했는데 **소급이 `eps`에만 걸리고 주식수에는 안 걸렸다.** 그래서
# 순위 축 가드(`guard-rebalance-on-dirty-rank.sh`)가 `BON 252배`로 리밸런싱을 막는다.
#
# | 행 | 기 | 저장 | 소스 | 근거 |
# |---|---|---|---|---|
# | 26231 | 2025 FY rev0 | 11,000 | **2,755,232** | 2025 팩트에 `dei:`도 `CommonStockSharesOutstanding`도 없다. `WeightedAverageNumberOfSharesOutstandingBasic`(2025-09-30)이 유일하고, 함의(−1,994,768/−0.72 = 2,770,511)와 **0.55%** 차다 |
# | 26230 | 2024 FY rev1 | 3,970,558 | **95,513** | 2026-01-23 제출이 FY2024를 병합 후 기준으로 재진술했다(`WeightedAverage*` 2,387,833 → 95,513). 함의(398,172/4.17 = 95,485)와 **0.03%** 차 |
#
# **저장된 11,000은 2025 팩트에 아예 없는 수다** — 옛 기의 값이 잘못 실렸다.
#
# ## 무엇이 안 바뀌는가
#
# `BON`은 봉도 지표도 0행이라 **다시 계산할 것이 없다.** 유니버스 순위는 `JdbcStoredShareCounts`가
# 재무를 직접 읽으므로 이 UPDATE만으로 반영된다. 그리고 수리해도 **편입은 안 된다** —
# 하한 5.256B을 넘으려면 2,755,232주에 주당 약 1,908달러가 필요하다.
#
#   ./ops/repair-bon-shares.sh          # 확인을 묻는다
#   ./ops/repair-bon-shares.sh --yes    # 묻지 않는다
set -euo pipefail

DB="${DB_NAME:-stock_screener}"
DUMP_DIR="$HOME/Library/Application Support/stock-screener/dumps"
STAMP=$(date +%Y%m%dT%H%M%SZ)

# 실행 시점에 근거를 다시 확인한다. 저장값이 이미 고쳐졌거나 다른 값이면 쓰지 않는다 —
# 이 스크립트가 서는 근거가 「지금 그 자리에 그 수가 있다」이기 때문이다.
expect_row() {
  local id="$1" want="$2"
  local got
  got=$(psql -d "$DB" -At -c "SELECT shares_outstanding::bigint FROM financial_statement WHERE id = $id" 2>/dev/null || true)
  if [ -z "$got" ]; then
    echo "[repair-bon] 행 $id 이 없다 — 아무것도 안 한다." >&2
    return 1
  fi
  if [ "$got" != "$want" ]; then
    echo "[repair-bon] 행 $id 의 값이 예상과 다르다: 지금 $got · 예상 $want" >&2
    echo "[repair-bon] 사이에 누가 고쳤다는 뜻이다. 근거를 다시 재고 이 스크립트를 고친다." >&2
    return 1
  fi
  return 0
}

ok=true
expect_row 26231 11000   || ok=false
expect_row 26230 3970558 || ok=false
if [ "$ok" != true ]; then
  echo "[repair-bon] 확인이 통과하지 못했다 — 쓰지 않았다."
  exit 1
fi

mkdir -p "$DUMP_DIR"
BEFORE="$DUMP_DIR/bon-shares-repair-$STAMP-before.psv"
psql -d "$DB" -At -F'|' -c "
SELECT f.id, s.ticker, f.fiscal_year, f.fiscal_period, f.revision_no, f.period_end,
       f.published_at, f.shares_outstanding, f.eps,
       COALESCE(f.net_income_controlling, f.net_income)
FROM financial_statement f JOIN stock s ON s.id = f.stock_id
WHERE f.id IN (26230, 26231) ORDER BY f.id" > "$BEFORE"
echo "[repair-bon] 되돌리기 파일: $BEFORE"
cat "$BEFORE"

if [ "${1:-}" != "--yes" ]; then
  read -r -p "[repair-bon] 26231: 11,000 → 2,755,232 · 26230: 3,970,558 → 95,513. 쓸까? [y/N] " answer
  case "$answer" in y|Y) ;; *) echo "[repair-bon] 안 썼다."; exit 0 ;; esac
fi

# MUST: 행 id로 쓴다. 「÷ N」으로 적으면 같은 종목의 다른 행이 두 번 나뉜다(ADR-0126 §규칙).
psql -d "$DB" -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
UPDATE financial_statement SET shares_outstanding = 2755232 WHERE id = 26231;
UPDATE financial_statement SET shares_outstanding =   95513 WHERE id = 26230;
COMMIT;
SQL

echo "[repair-bon] 수리 후 — 함의/저장 배수가 1에 가까워야 한다:"
psql -d "$DB" -At -F'|' -c "
SELECT f.id, f.fiscal_year, f.revision_no, f.shares_outstanding, f.eps,
       round(COALESCE(f.net_income_controlling, f.net_income) / NULLIF(f.eps, 0)) AS 함의,
       round((COALESCE(f.net_income_controlling, f.net_income) / NULLIF(f.eps, 0))
             / f.shares_outstanding, 4) AS 배수
FROM financial_statement f WHERE f.id IN (26230, 26231) ORDER BY f.id"

echo "[repair-bon] 순위 축 가드가 지금 무엇을 신고하는가:"
psql -d "$DB" -At -F'|' -c "
WITH cur AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding AS sh,
         COALESCE(f.net_income_controlling, f.net_income) / NULLIF(f.eps, 0) AS imp
  FROM financial_statement f WHERE f.shares_outstanding > 0
  ORDER BY f.stock_id, f.period_end DESC, f.revision_no DESC)
SELECT COALESCE(string_agg(s.ticker || ' ' || round(c.imp / c.sh, 0) || '배', ', '), '(없다)')
FROM cur c JOIN stock s ON s.id = c.stock_id
WHERE c.imp IS NOT NULL AND c.imp / c.sh >= 100 AND c.imp / c.sh < 10000"

echo "[repair-bon] 끝. 이 스크립트는 할 일이 없어졌다 — git rm ops/repair-bon-shares.sh"
