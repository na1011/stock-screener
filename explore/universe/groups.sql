-- 단위증권 실측 — CIK마다 티커가 둘 이상이고 그중 하나라도 주식수가 있는 발행사.
-- 주식수 술어는 JdbcStoredShareCounts.SQL의 latest를 복제(기준일 = 오늘 · 기말 2년 안 · 활성 · 주식수 > 0).
CREATE TEMP TABLE cik(cik bigint, ticker text, exch text);
\copy cik FROM 'cik.csv' CSV
WITH listed AS (
  SELECT s.id, s.ticker, s.name, s.sic_code FROM stock s
  WHERE s.market_code = 'US' AND s.is_active
    AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id = s.id AND m.universe_code = 'US_LISTED' AND m.effective_to IS NULL)),
sh AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding, f.period_end
  FROM financial_statement f JOIN stock s ON s.id = f.stock_id
  WHERE s.market_code = 'US' AND s.is_active AND f.published_at <= current_date
    AND f.shares_outstanding > 0 AND f.period_end >= current_date - interval '2 years'
  ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC),
j AS (
  SELECT c.cik, l.ticker, l.name, l.sic_code, sh.shares_outstanding sh,
         (SELECT p.close FROM price_daily p WHERE p.stock_id = l.id ORDER BY p.trade_date DESC LIMIT 1) close,
         (SELECT string_agg(m.origin || ':' || m.effective_from || '~' || coalesce(m.effective_to::text, ''), ' ' ORDER BY m.effective_from)
            FROM universe_membership m WHERE m.stock_id = l.id AND m.universe_code = 'US_TOP1000') top
  FROM listed l JOIN cik c ON c.ticker = l.ticker LEFT JOIN sh ON sh.stock_id = l.id)
SELECT j.* FROM j WHERE j.cik IN (
  SELECT cik FROM j GROUP BY cik HAVING count(*) > 1 AND count(sh) > 0)
ORDER BY j.cik, j.ticker;
