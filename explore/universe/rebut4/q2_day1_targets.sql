-- 계획 v4 B-1: 초기값(US #683 · KOSPI #645 · KOSDAQ #658 — 그 시각 활성 편입)으로 채운 뒤 첫 평일 술어 대상
-- 술어: 활성 유니버스 · 열린 행 · 활성 종목 · 수집 시장(FR resolveTargets와 같은 모집단) · (synced_at IS NULL OR effective_from > synced_at)
WITH runs(universe_code, run_id) AS (VALUES ('US_TOP1000',683),('KOSPI_TOP200',645),('KOSDAQ_TOP150',658)),
r AS (SELECT runs.universe_code, b.id, b.started_at FROM runs JOIN batch_execution b ON b.id=runs.run_id),
init AS ( -- 그 시각 활성 편입 → synced_at = 그 실행 시작(보수적) 
  SELECT m.stock_id, max(r.started_at) synced_at
  FROM r JOIN universe_membership m ON m.universe_code=r.universe_code AND m.created_at <= r.started_at
       AND m.effective_from <= (r.started_at AT TIME ZONE 'Asia/Seoul')::date
       AND (m.effective_to IS NULL OR m.effective_to > (r.started_at AT TIME ZONE 'Asia/Seoul')::date)
  GROUP BY 1),
cur AS (
  SELECT m.stock_id, m.universe_code, m.effective_from, m.created_at, m.origin, s.ticker, s.market_code
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active AND s.market_code IN ('KOSPI','KOSDAQ','US'))
SELECT c.universe_code, c.ticker, c.effective_from, c.origin, i.synced_at::timestamp(0) synced_at,
       CASE WHEN i.synced_at IS NULL THEN 'NULL' ELSE 'start>synced' END why
FROM cur c LEFT JOIN init i ON i.stock_id=c.stock_id
WHERE i.synced_at IS NULL OR c.effective_from > (i.synced_at AT TIME ZONE 'Asia/Seoul')::date
ORDER BY 1,3,2;
