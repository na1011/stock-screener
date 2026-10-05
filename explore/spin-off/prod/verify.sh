#!/bin/bash
# 분사 배포 검증 — 운영 DB SELECT만. 기대값(드라이런 · 대조 전에 적음):
#   승인 분사 중 price_ratio 없는 행 0 · HON 2026-06-29 사건일 -3.54% · 000880 2026-08-25 +20.91%
#   DD 2025-11-03 +1.89% · FDX 2026-06-01 +0.37% · CTVA 최신일 RETURN_52W ≈ +19.52 · 207940 최신일 지표 ≈ 147행
#   LEN·FTI·LBTYB·SYRE 지표 복원 · DNTH·CNH 억제(UNSUPPORTED)
PSQL=/opt/homebrew/opt/postgresql@16/bin/psql
q() { echo "## $1"; $PSQL -d stock_screener -v ON_ERROR_STOP=1 -AtF $'\t' -c "$2"; }
q "원장 분사·미상 수" "select type, status, count(*), count(price_ratio) from corporate_action where type in ('SPIN_OFF','UNKNOWN') group by 1,2 order by 1,2"
q "승인·적용 분사 중 가격 배수 없음" "select s.ticker, ca.effective_date, ca.status from corporate_action ca join stock s on s.id=ca.stock_id where ca.type='SPIN_OFF' and ca.status in ('APPROVED','APPLIED') and ca.price_ratio is null"
q "분배 표 행" "select count(*), count(parent_value) from corporate_action_distribution" 2>/dev/null
q "사건일 수정 수익률(%)" "with ev(t,d) as (values ('HON','2026-06-29'::date),('000880','2026-08-25'),('DD','2025-11-03'),('FDX','2026-06-01'),('207940','2025-11-24'))
 select ev.t, ev.d, round((p.adj_close/pp.adj_close-1)*100,2), p.adj_factor
 from ev join stock s on s.ticker=ev.t join price_daily p on p.stock_id=s.id and p.trade_date=ev.d
 join lateral (select adj_close from price_daily x where x.stock_id=s.id and x.trade_date<ev.d order by trade_date desc limit 1) pp on true order by 1"
q "최신일 지표 행 수 · CTVA RETURN_52W" "with lat as (select s.id, s.ticker, (select max(trade_date) from indicator_value v where v.stock_id=s.id) d from stock s where s.ticker in ('CTVA','207940','LEN','FTI','LBTYB','SYRE','DNTH','CNH','HON','DD','FTV','DELL','000880'))
 select lat.ticker, lat.d, (select count(*) from indicator_value v where v.stock_id=lat.id and v.trade_date=lat.d),
  (select round(value,2) from indicator_value v where v.stock_id=lat.id and v.trade_date=lat.d and v.indicator_code='RETURN_52W') from lat order by 1"
q "HON MARKET_CAP(십억)" "select v.trade_date, round(v.value/1e9,1) from indicator_value v join stock s on s.id=v.stock_id where s.ticker='HON' and v.indicator_code='MARKET_CAP' and v.trade_date in ('2026-06-26','2026-06-29','2026-07-22') order by 1"
