-- B v5 첫 평일 대상 예측 — 초기 표지(runs-cover.sql의 덮은 실행 중 유니버스별 마지막) → 술어(FR resolveTargets 조각 · 시작 = 열린 행 max(effective_from) > 표지 날짜).
WITH runs AS (
  SELECT id, job_name, started_at, (started_at AT TIME ZONE 'Asia/Seoul')::date d, target_count
  FROM batch_execution
  WHERE job_name IN ('financial-refill','full-statement-refill') AND status = 'SUCCESS' AND failure_count = 0
    AND (substring(note from '연도=\d{4}~(\d{4})'))::int - (substring(note from '연도=(\d{4})~'))::int >= 8
    AND (job_name = 'financial-refill' OR note LIKE '%Q1%')),
mem AS (
  SELECT r.id, m.universe_code, m.stock_id
  FROM runs r JOIN universe_membership m ON m.effective_from <= r.d AND (m.effective_to IS NULL OR m.effective_to > r.d)
    AND m.created_at <= r.started_at
  JOIN universe u ON u.code = m.universe_code AND u.is_active JOIN stock s ON s.id = m.stock_id AND s.is_active),
cover AS (
  SELECT r.id, r.job_name, r.started_at, x.universe_code
  FROM runs r JOIN (SELECT id, universe_code, count(DISTINCT stock_id) n FROM mem GROUP BY 1,2) x ON x.id = r.id AND x.n = r.target_count),
last_cover AS (SELECT DISTINCT ON (job_name, universe_code) * FROM cover ORDER BY job_name, universe_code, started_at DESC),
marker AS (
  SELECT mem.stock_id, lc.job_name, max(lc.started_at) synced_at
  FROM last_cover lc JOIN mem ON mem.id = lc.id AND mem.universe_code = lc.universe_code GROUP BY 1,2),
cur AS (
  SELECT s.id, s.ticker, s.market_code, max(m.effective_from) start
  FROM universe_membership m JOIN stock s ON s.id = m.stock_id JOIN universe u ON u.code = m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active GROUP BY 1,2,3)
SELECT j.job, c.market_code, count(*) FILTER (WHERE mk.synced_at IS NULL OR c.start > (mk.synced_at AT TIME ZONE 'Asia/Seoul')::date) targets,
       count(*) members,
       string_agg(c.ticker || '(' || c.start || ' ' || coalesce(to_char(mk.synced_at AT TIME ZONE 'Asia/Seoul','MM-DD'),'NULL') || ')', ' ' ORDER BY c.ticker)
         FILTER (WHERE mk.synced_at IS NULL OR c.start > (mk.synced_at AT TIME ZONE 'Asia/Seoul')::date) who,
       (SELECT string_agg(lc.universe_code || '←#' || lc.id, ' ') FROM last_cover lc WHERE lc.job_name = j.job) runs
FROM cur c CROSS JOIN (VALUES ('financial-refill'), ('full-statement-refill')) j(job)
LEFT JOIN marker mk ON mk.stock_id = c.id AND mk.job_name = j.job
WHERE j.job = 'financial-refill' OR c.market_code <> 'US'
GROUP BY j.job, c.market_code ORDER BY 1, 2;
