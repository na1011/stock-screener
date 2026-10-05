SET statement_timeout='60s';
-- 현 KR 편입 중, 30일 창 안에 공시일이 든 보고서를 가진 종목 수 = 그 창 끝 다음 날 정기 FR이 「신규」를 낼 하한
WITH kr AS (
  SELECT DISTINCT m.stock_id FROM universe_membership m JOIN stock s ON s.id=m.stock_id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active AND s.market_code IN ('KOSPI','KOSDAQ'))
SELECT w.lo, w.hi,
  count(DISTINCT f.stock_id) AS stocks_with_new_report,
  (SELECT count(*) FROM kr) AS kr_members
FROM (VALUES (date '2026-04-16', date '2026-05-15'), (date '2026-07-16', date '2026-08-14'),
             (date '2026-02-15', date '2026-03-31'), (date '2026-08-15', date '2026-09-14')) w(lo, hi)
LEFT JOIN financial_statement f ON f.stock_id IN (SELECT stock_id FROM kr)
     AND f.published_at BETWEEN w.lo AND w.hi AND f.revision_no = 0
GROUP BY w.lo, w.hi ORDER BY w.lo;
