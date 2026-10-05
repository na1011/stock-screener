SET statement_timeout='60s';
-- 성질 펜스 시험: OBSERVED 편출 중 「편출일 DB 시총 > 그날 남은 편입의 DB 시총 하위 5% 값 × 1.5」 — 손 목록 없이 큰 종목 편출을 잡는가
WITH d AS (SELECT DISTINCT effective_to d FROM universe_membership WHERE universe_code='US_TOP1000' AND origin='OBSERVED' AND effective_to IS NOT NULL),
cap AS (
  SELECT d.d, m.stock_id, coalesce(m.effective_to = d.d, false) removed,
    (SELECT f.shares_outstanding FROM financial_statement f WHERE f.stock_id=m.stock_id AND f.shares_outstanding>0 AND f.period_end >= d.d - 730 AND f.published_at <= d.d ORDER BY f.period_end DESC, f.published_at DESC, f.revision_no DESC LIMIT 1)
    * (SELECT p.close FROM price_daily p WHERE p.stock_id=m.stock_id AND p.trade_date < d.d ORDER BY p.trade_date DESC LIMIT 1) c
  FROM d JOIN universe_membership m ON m.universe_code='US_TOP1000' AND m.origin='OBSERVED'
   AND m.effective_from < d.d AND (m.effective_to IS NULL OR m.effective_to >= d.d)),
b AS (SELECT d, percentile_cont(0.05) WITHIN GROUP (ORDER BY c) p5 FROM cap WHERE NOT removed AND c IS NOT NULL GROUP BY d)
SELECT cap.d, count(*) FILTER (WHERE removed) removed_n,
  count(*) FILTER (WHERE removed AND c > b.p5*1.5) big_removed,
  string_agg(CASE WHEN removed AND c > b.p5*1.5 THEN s.ticker||'='||round((c/1e9)::numeric,1) END, ',') who,
  round((b.p5/1e9)::numeric,2) p5_b
FROM cap JOIN b USING (d) JOIN stock s ON s.id=cap.stock_id GROUP BY cap.d, b.p5 ORDER BY 1;
