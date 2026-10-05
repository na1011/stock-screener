-- 리밸런싱(관측)마다 유니버스별 새 편입 수 · 편출 수
SELECT effective_from AS d, universe_code, count(*) AS added
FROM universe_membership
WHERE origin='OBSERVED' AND effective_from >= '2026-08-01'
GROUP BY 1,2 ORDER BY 1,2;
SELECT effective_to AS d, universe_code, count(*) AS removed
FROM universe_membership
WHERE origin='OBSERVED' AND effective_to >= '2026-08-01'
GROUP BY 1,2 ORDER BY 1,2;
SELECT job_name, target_date, status, triggered_by, started_at, finished_at, note
FROM batch_execution WHERE job_name='universe-rebalance' AND started_at > '2026-08-25' ORDER BY started_at;
