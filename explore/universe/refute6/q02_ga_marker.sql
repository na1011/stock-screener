-- (나) 술어 「원장 최신 공시 > 동기 시각」을 v6 표지(synced_at = FR 성공 시각)로 셀 때 (가) 80종목이 대상에 드는가.
-- marker 조각 = b5/targets.sql 그대로. (가) 80 = refute5/q04 술어 그대로. 6월 분기 공시일은 원장에 안 물었다 —
-- 상한으로 법정 기한 08-14를 쓴다(ABT·KO·NEE·DUK 실측 07-24~08-04 — refute5).
SET statement_timeout='60s';
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
cur AS (SELECT DISTINCT s.id, s.ticker FROM universe_membership m JOIN stock s ON s.id=m.stock_id
        JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active AND s.market_code='US'),
f AS (SELECT stock_id, max(period_end) pe FROM financial_statement GROUP BY 1),
ga AS (SELECT c.id, c.ticker FROM cur c JOIN f ON f.stock_id=c.id WHERE f.pe BETWEEN date '2026-03-01' AND date '2026-04-10')
SELECT count(*) n_ga,
       count(*) FILTER (WHERE mk.synced_at IS NULL) no_marker,
       count(*) FILTER (WHERE (mk.synced_at AT TIME ZONE 'Asia/Seoul')::date > date '2026-08-14') marker_after_q2_deadline,
       min(mk.synced_at) min_sync, max(mk.synced_at) max_sync
FROM ga LEFT JOIN marker mk ON mk.stock_id = ga.id AND mk.job_name = 'financial-refill';
