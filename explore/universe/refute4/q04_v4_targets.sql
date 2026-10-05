-- v4 술어(현 편입 행 시작 > 마지막 재무 갱신)의 초기 대상: 계획의 초기값 규칙(전량 실행 이력 · 그 시각의 활성 편입)
-- US: #683(09-28 01:14) · 그 시각 명부 = 09-25 asOf 행
-- KOSPI_TOP200: #645(09-21 22:15) · KOSDAQ_TOP150: #658(09-22 09:43) 또는 #646 — 그 시각 명부 = 09-18 asOf 행
WITH init AS (
  SELECT 'US_TOP1000'::text uc, timestamptz '2026-09-28 01:14+09' synced, date '2026-09-25' cover
  UNION ALL SELECT 'KOSPI_TOP200', '2026-09-21 22:15+09', date '2026-09-18'
  UNION ALL SELECT 'KOSDAQ_TOP150', '2026-09-22 09:43+09', date '2026-09-18'
), cur AS (
  SELECT m.universe_code uc, m.stock_id, s.ticker, m.effective_from, m.origin
  FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
  JOIN stock s ON s.id=m.stock_id
  WHERE m.effective_to IS NULL
), was AS (
  SELECT DISTINCT m.universe_code uc, m.stock_id FROM universe_membership m JOIN init i ON i.uc=m.universe_code
  WHERE m.effective_from <= i.cover AND (m.effective_to IS NULL OR m.effective_to > i.cover)
)
SELECT c.uc, count(*) cur_rows,
  count(*) FILTER (WHERE w.stock_id IS NULL) init_null,
  count(*) FILTER (WHERE w.stock_id IS NOT NULL AND c.effective_from > (i.synced AT TIME ZONE 'Asia/Seoul')::date) start_after_sync,
  string_agg(c.ticker||':'||c.effective_from, ' ' ORDER BY c.effective_from) FILTER (WHERE w.stock_id IS NULL OR c.effective_from > (i.synced AT TIME ZONE 'Asia/Seoul')::date) targets
FROM cur c JOIN init i ON i.uc=c.uc LEFT JOIN was w ON w.uc=c.uc AND w.stock_id=c.stock_id
GROUP BY c.uc;
-- 현 편입 행 시작일 분포
SELECT m.universe_code, m.effective_from, m.origin, count(*) FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
WHERE m.effective_to IS NULL GROUP BY 1,2,3 ORDER BY 1,2;
