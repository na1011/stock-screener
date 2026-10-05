-- 10-05(월) 정기 사슬의 시각과 소요 — F6를 14:18 첫 단계에 넣을 때의 여유
SELECT id, job_name, triggered_by, status, target_count, started_at::time(0) st, finished_at::time(0) fin, (finished_at-started_at)::interval(0) dur
FROM batch_execution WHERE started_at >= '2026-10-05 13:00' AND started_at < '2026-10-06' ORDER BY id;
