-- F6 첫 평일 대상 21(b5/targets.sql 결과의 티커)의 재무 최신성: 최신 기말·최신 공시일·마지막 행 생성 시각 · 대조군 = 같은 시장 현 편입 중앙
SET statement_timeout='60s';
WITH t(ticker) AS (VALUES ('AGX'),('AXTI'),('BB'),('FTDR'),('GVA'),('IOVA'),('KLIC'),('KOD'),('LAUR'),('NAVN'),('NJR'),('TFX'),('VSH'),
  ('001740'),('007810'),('077360'),('126340'),('253590'),('092870'),('456160'),('490470')),
cur AS (SELECT DISTINCT s.id, s.ticker, s.market_code FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active
        WHERE m.effective_to IS NULL AND s.is_active),
f AS (SELECT stock_id, max(period_end) pe, max(published_at) pub, max(created_at) cr, count(*) n FROM financial_statement GROUP BY 1)
SELECT c.market_code, (t.ticker IS NOT NULL) is_target, count(*) n,
  percentile_disc(0.5) WITHIN GROUP (ORDER BY f.pe) med_pe,
  count(*) FILTER (WHERE f.pe < date '2026-06-01') pe_before_jun,
  string_agg(c.ticker||':'||coalesce(f.pe::text,'-')||'/'||coalesce(f.pub::text,'-')||'/'||coalesce(to_char(f.cr AT TIME ZONE 'Asia/Seoul','MM-DD'),'-'), ' ' ORDER BY c.ticker) FILTER (WHERE t.ticker IS NOT NULL) detail
FROM cur c LEFT JOIN t ON t.ticker=c.ticker LEFT JOIN f ON f.stock_id=c.id
GROUP BY 1,2 ORDER BY 1,2;
