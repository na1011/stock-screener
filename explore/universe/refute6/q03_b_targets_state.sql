-- B 첫 평일 대상 21(v5 b5/targets.sql 결과 명단)의 재무 상태 · 최신일 FUNDAMENTAL 칸.
SET statement_timeout='60s';
WITH t(ticker, mkt) AS (VALUES ('AGX','US'),('AXTI','US'),('BB','US'),('FTDR','US'),('GVA','US'),('IOVA','US'),('KLIC','US'),('KOD','US'),
  ('LAUR','US'),('NAVN','US'),('NJR','US'),('TFX','US'),('VSH','US'),('001740','KOSPI'),('007810','KOSPI'),('077360','KOSDAQ'),('126340','KOSDAQ'),
  ('253590','KOSDAQ'),('092870','KOSDAQ'),('456160','KOSDAQ'),('490470','KOSDAQ')),
s AS (SELECT st.id, st.ticker, st.market_code FROM stock st JOIN t ON t.ticker=st.ticker AND t.mkt=st.market_code),
f AS (SELECT stock_id, count(*) n, max(period_end) pe, min(created_at)::date c0, max(created_at)::date c1 FROM financial_statement GROUP BY 1),
iv AS (SELECT stock_id, count(*) FILTER (WHERE trade_date = (SELECT max(trade_date) FROM indicator_trade_date x WHERE x.market_code = CASE WHEN st.market_code='US' THEN 'US' ELSE st.market_code END)) latest_cells
       FROM indicator_value v JOIN stock st ON st.id=v.stock_id JOIN indicator i ON i.code=v.indicator_code AND i.category='FUNDAMENTAL'
       WHERE v.stock_id IN (SELECT id FROM s) AND v.trade_date >= date '2026-09-25' GROUP BY 1)
SELECT s.market_code, s.ticker, f.n, f.pe, f.c0, f.c1, iv.latest_cells FROM s LEFT JOIN f ON f.stock_id=s.id LEFT JOIN iv ON iv.stock_id=s.id ORDER BY 1,2;
