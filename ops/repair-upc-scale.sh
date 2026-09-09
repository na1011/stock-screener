#!/bin/bash
#
# `UPC`의 2026-01-29 제출본 세 행에서 **금액 열 전부를 ÷1000** 한다.
#
# **에이전트가 못 써서 있는 스크립트다** — 운영 DB 쓰기가 분류기에 막힌다. 사람이 실행한다.
# **한 번 쓰고 지운다.**
#
# ## 무엇이 틀렸나
#
# 제출자의 inline XBRL `scale` 속성이 한 제출 전체에 잘못 붙었다(ADR-0126 §결정이 적은 그 병).
# `companyfacts`만 그 속성을 곱해 주므로 EDGAR 뷰어에서는 안 보인다. **주식수가 아니라
# 금액 칸에 붙은 것이 이번 건이다.**
#
# ## 근거 셋
#
# ⓒ **유효숫자 동일성** — FY2024를 앞 제출(2025-04-29)과 열별로 대조하니 **14개 열 전부
#    정확히 ×1000**이다(`revenue` 23,024,458 → 23,024,458,000 등). 부분이 아니라 제출 전체다.
# ⓔ **소스의 같은 기, 다른 제출** — `NetIncomeLoss`(end=2024-09-30)가
#    2025-04-29 제출에서 **−8,727,298**, 2026-01-29 제출에서 **−8,727,298,000**이다.
# ⓑ **함의 검산** — `net_income / eps`가 주식수와 맞아야 한다. **`eps`는 가중평균 기준**이므로
#    `dei:`가 아니라 `WeightedAverageNumberOfSharesOutstandingBasic`과 견준다:
#    FY2025 −3,672,055 / −8.06 = **455,590** 대 소스 **455,871**(0.06% 차),
#    FY2024 −8,727,298 / −626.96 = **13,920** 대 소스 **13,920**(일치).
#    ÷1000 하지 않으면 둘 다 정확히 1000배 어긋난다.
#
# **`eps`와 `shares_outstanding`은 안 건드린다** — 앞뒤 제출이 같은 값이라 축척을 안 받았다.
#
# ## 무엇이 바뀌나
#
# `UPC`는 비편입이고 지표가 0행이라 **다시 계산할 것이 없다.** 바뀌는 것은 순위 축 가드다 —
# 수리 뒤 함의/저장 배수가 **809 → 약 0.81**이 되어 `--job=universe`가 열린다.
#
#   ./ops/repair-upc-scale.sh          # 확인을 묻는다
#   ./ops/repair-upc-scale.sh --yes    # 묻지 않는다
set -euo pipefail

DB="${DB_NAME:-stock_screener}"
DUMP_DIR="$HOME/Library/Application Support/stock-screener/dumps"
STAMP=$(date +%Y%m%dT%H%M%SZ)
IDS="174571, 174570, 174568"

# 실행 시점에 근거를 다시 확인한다 — 사이에 누가 고쳤으면 쓰면 안 된다.
check=$(psql -d "$DB" -At -c "
SELECT count(*) FROM financial_statement
WHERE id IN ($IDS) AND net_income IN (-3672055000, -8727298000, -6581024000)")
if [ "$check" != "3" ]; then
  echo "[repair-upc] 세 행의 net_income이 예상과 다르다(맞는 행 $check/3) — 쓰지 않았다." >&2
  echo "[repair-upc] 근거를 다시 재고 이 스크립트를 고친다." >&2
  exit 1
fi

# ⓒ를 실행 시점에 다시 세운다: FY2024의 앞 제출 대비 비가 열마다 1000인가.
ratios=$(psql -d "$DB" -At -c "
WITH a AS (SELECT * FROM financial_statement WHERE id = 174569),
     b AS (SELECT * FROM financial_statement WHERE id = 174570)
SELECT count(*) FILTER (WHERE round((bv / NULLIF(av, 0))::numeric, 2) <> 1000.00)
FROM (SELECT a.revenue av, b.revenue bv FROM a, b
      UNION ALL SELECT a.net_income, b.net_income FROM a, b
      UNION ALL SELECT a.total_assets, b.total_assets FROM a, b
      UNION ALL SELECT a.total_equity, b.total_equity FROM a, b
      UNION ALL SELECT a.operating_cash_flow, b.operating_cash_flow FROM a, b) z
WHERE bv IS NOT NULL")
if [ "$ratios" != "0" ]; then
  echo "[repair-upc] FY2024 대조에서 ×1000이 아닌 열이 $ratios 개다 — 쓰지 않았다." >&2
  exit 1
fi

mkdir -p "$DUMP_DIR"
BEFORE="$DUMP_DIR/upc-scale-repair-$STAMP-before.psv"
psql -d "$DB" -At -F'|' -c "
SELECT f.id, s.ticker, f.fiscal_year, f.fiscal_period, f.revision_no, f.published_at,
       f.revenue, f.operating_income, f.net_income, f.total_assets, f.total_equity,
       f.total_liabilities, f.current_assets, f.current_liabilities, f.retained_earnings,
       f.operating_cash_flow, f.cost_of_sales, f.cash_and_equivalents, f.capex, f.inventories
FROM financial_statement f JOIN stock s ON s.id = f.stock_id
WHERE f.id IN ($IDS) ORDER BY f.id" > "$BEFORE"
echo "[repair-upc] 되돌리기 파일: $BEFORE"
cat "$BEFORE"

if [ "${1:-}" != "--yes" ]; then
  read -r -p "[repair-upc] 세 행의 금액 열을 전부 ÷1000 한다. 쓸까? [y/N] " answer
  case "$answer" in y|Y) ;; *) echo "[repair-upc] 안 썼다."; exit 0 ;; esac
fi

# MUST: 행 id로 쓴다(ADR-0126 §규칙). NULL은 그대로 둔다 — 나누면 NULL이라 안전하지만
# 명시적으로 적어 두는 편이 다음에 이 스크립트를 읽는 쪽에 낫다.
psql -d "$DB" -v ON_ERROR_STOP=1 <<SQL
BEGIN;
UPDATE financial_statement SET
  revenue                = revenue / 1000,
  operating_income       = operating_income / 1000,
  net_income             = net_income / 1000,
  net_income_controlling = net_income_controlling / 1000,
  total_assets           = total_assets / 1000,
  total_equity           = total_equity / 1000,
  total_liabilities      = total_liabilities / 1000,
  current_assets         = current_assets / 1000,
  current_liabilities    = current_liabilities / 1000,
  retained_earnings      = retained_earnings / 1000,
  operating_cash_flow    = operating_cash_flow / 1000,
  cost_of_sales          = cost_of_sales / 1000,
  cash_and_equivalents   = cash_and_equivalents / 1000,
  capex                  = capex / 1000,
  inventories            = inventories / 1000,
  gross_profit           = gross_profit / 1000,
  operating_expenses     = operating_expenses / 1000,
  costs_and_expenses     = costs_and_expenses / 1000
WHERE id IN ($IDS);
COMMIT;
SQL

echo "[repair-upc] 수리 후 — 함의/저장 배수가 100 아래여야 한다:"
psql -d "$DB" -At -F'|' -c "
SELECT f.id, f.fiscal_year, f.net_income, f.eps,
       round(f.net_income / NULLIF(f.eps, 0)) AS 함의,
       round(((f.net_income / NULLIF(f.eps, 0)) / f.shares_outstanding)::numeric, 3) AS 배수
FROM financial_statement f WHERE f.id IN ($IDS) ORDER BY f.id"

echo "[repair-upc] 순위 축 가드가 지금 무엇을 신고하는가:"
psql -d "$DB" -At -c "
WITH cur AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding AS sh,
         COALESCE(f.net_income_controlling, f.net_income) / NULLIF(f.eps, 0) AS imp
  FROM financial_statement f WHERE f.shares_outstanding > 0
  ORDER BY f.stock_id, f.period_end DESC, f.revision_no DESC)
SELECT COALESCE(string_agg(s.ticker || ' ' || round(c.imp / c.sh, 0) || '배', ', '), '(없다 — 가드가 열린다)')
FROM cur c JOIN stock s ON s.id = c.stock_id
WHERE c.imp IS NOT NULL AND c.imp / c.sh >= 100 AND c.imp / c.sh < 10000"

echo "[repair-upc] 끝. 이 스크립트는 할 일이 없어졌다 — git rm ops/repair-upc-scale.sh"
