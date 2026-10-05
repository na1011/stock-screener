SET statement_timeout='60s';
-- 초기값 재구성(#683 → US) 대 독립 흔적: financial_column_absence.observed_at(SEC FR이 종목마다 LocalDate.now()로 씀 · 부재가 있을 때만)
WITH mem683 AS (
  SELECT DISTINCT m.stock_id FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
  JOIN stock s ON s.id=m.stock_id AND s.is_active
  WHERE m.universe_code='US_TOP1000' AND m.effective_from <= '2026-09-28' AND (m.effective_to IS NULL OR m.effective_to > '2026-09-28')
    AND m.created_at <= (SELECT started_at FROM batch_execution WHERE id=683)),
cur AS (SELECT DISTINCT m.stock_id FROM universe_membership m WHERE m.universe_code='US_TOP1000' AND m.effective_to IS NULL),
ab AS (SELECT stock_id, max(observed_at) obs FROM financial_column_absence WHERE source_name ILIKE '%sec%' GROUP BY 1)
SELECT (c.stock_id IN (SELECT stock_id FROM mem683)) in_recon, ab.obs, count(*)
FROM cur c LEFT JOIN ab ON ab.stock_id=c.stock_id GROUP BY 1,2 ORDER BY 1,2;
SELECT DISTINCT source_name FROM financial_column_absence;
-- 09-28에 관측된 종목 중 재구성 밖(=#683이 처리했는데 재구성이 놓친 것)
WITH mem683 AS (
  SELECT DISTINCT m.stock_id FROM universe_membership m
  JOIN stock s ON s.id=m.stock_id AND s.is_active
  WHERE m.universe_code='US_TOP1000' AND m.effective_from <= '2026-09-28' AND (m.effective_to IS NULL OR m.effective_to > '2026-09-28')
    AND m.created_at <= (SELECT started_at FROM batch_execution WHERE id=683))
SELECT s.ticker, s.is_active, a.observed_at FROM financial_column_absence a JOIN stock s ON s.id=a.stock_id
WHERE a.observed_at='2026-09-28' AND a.stock_id NOT IN (SELECT stock_id FROM mem683) GROUP BY 1,2,3;
