SET statement_timeout='60s';
-- 21 대상의 지금 상태: 최신 기말 · 최신 공시일 · 재무 행 created_at 최대(마지막으로 재무가 쓰인 시각의 하한)
WITH t(ticker) AS (VALUES ('AGX'),('AXTI'),('BB'),('FTDR'),('GVA'),('IOVA'),('KLIC'),('KOD'),('LAUR'),('NAVN'),('NJR'),('TFX'),('VSH'),
  ('001740'),('007810'),('077360'),('126340'),('253590'),('092870'),('456160'),('490470'))
SELECT s.ticker, s.market_code, max(f.period_end) FILTER (WHERE f.is_latest) last_period, max(f.published_at) last_pub,
       to_char(max(f.created_at) AT TIME ZONE 'Asia/Seoul','MM-DD HH24:MI') last_row_created,
       count(*) FILTER (WHERE f.is_latest AND f.fiscal_period<>'FY' AND f.total_equity_controlling IS NULL AND s.market_code<>'US') kr_q_ctrl_null
FROM t JOIN stock s ON s.ticker=t.ticker AND s.is_active LEFT JOIN financial_statement f ON f.stock_id=s.id
GROUP BY 1,2 ORDER BY 2,1;
