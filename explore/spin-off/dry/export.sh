#!/bin/bash
# 사본 하나의 비교 재료를 TSV로 낸다: 원장 · 분배 원자료 · 종목별 수정계수 지문 · 지표 지문(시장·날짜별).
set -euo pipefail
DB=$1; OUT=$2; mkdir -p $OUT
PSQL="/opt/homebrew/opt/postgresql@16/bin/psql -d $DB -At -F $'\t' -v ON_ERROR_STOP=1"
export PGOPTIONS="-c statement_timeout=0"
$PSQL -c "select s.market_code, s.ticker, ca.effective_date, ca.type, ca.status, ca.ratio, coalesce(ca.price_ratio::text,''), coalesce(ca.source_form,''), ca.detection_source from corporate_action ca join stock s on s.id=ca.stock_id order by 1,2,3" > $OUT/ledger.tsv 2>/dev/null || $PSQL -c "select s.market_code, s.ticker, ca.effective_date, ca.type, ca.status, ca.ratio, '', coalesce(ca.source_form,''), ca.detection_source from corporate_action ca join stock s on s.id=ca.stock_id order by 1,2,3" > $OUT/ledger.tsv
$PSQL -c "select s.ticker, ca.effective_date, d.new_symbol, d.rate, d.value_date, d.parent_value, d.child_value from corporate_action_distribution d join corporate_action ca on ca.id=d.corporate_action_id join stock s on s.id=ca.stock_id order by 1,2" > $OUT/distribution.tsv 2>/dev/null || : > $OUT/distribution.tsv
# 종목별 수정계수: 계수가 바뀌는 날짜 경계 목록(계단) — 같으면 지문이 같다.
$PSQL -c "select s.market_code, s.ticker, min(p.trade_date), max(p.trade_date), p.adj_factor, count(*) from price_daily p join stock s on s.id=p.stock_id group by 1,2,5 order by 1,2,3" > $OUT/factors.tsv
# 지표 지문: (시장, 날짜, 코드)별 값 합·행 수 — 어느 칸이 움직였는지 귀속한다.
$PSQL -c "select s.market_code, iv.stock_id, s.ticker, iv.indicator_code, count(*), md5(string_agg(iv.trade_date::text||':'||iv.value::text, ',' order by iv.trade_date)) from indicator_value iv join stock s on s.id=iv.stock_id group by 1,2,3,4" > $OUT/indicators.tsv
echo "exported $DB → $OUT"
