-- 0콜 · 운영 SELECT: governance-load 성공 행 전부(트리거·대상 수) — G에는 유니버스 인자가 없어(GovernanceLoadJob.run) 대상 < 편입 수면 티커 좁힘
\pset pager off
SELECT id, triggered_by, status, target_count, started_at, finished_at-started_at dur
FROM batch_execution WHERE job_name='governance-load' AND status IN ('SUCCESS','PARTIAL_SUCCESS') ORDER BY id;
SELECT triggered_by, count(*), count(*) FILTER (WHERE target_count < 350) lt350 FROM batch_execution
WHERE job_name='governance-load' AND status IN ('SUCCESS','PARTIAL_SUCCESS') GROUP BY 1;
