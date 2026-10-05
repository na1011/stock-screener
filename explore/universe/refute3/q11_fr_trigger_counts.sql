-- FR 트리거별 성공 수 — 시계 판정이 무엇을 세는가([A56])
SELECT triggered_by, count(*) FILTER (WHERE status IN ('SUCCESS','PARTIAL_SUCCESS')) ok, max(started_at)::timestamp(0) last_ok
FROM batch_execution WHERE job_name='financial-refill' GROUP BY 1;
-- CLI indicator-reconcile 경로(유도가 MANUAL로 남은 governance-derive) 사용 빈도 — 10-04 화해 배포 뒤
SELECT started_at::date d, count(*) FROM batch_execution WHERE job_name='governance-derive' AND triggered_by='MANUAL' AND started_at >= '2026-10-04' GROUP BY 1 ORDER BY 1;
-- RUNNING으로 남은 행(스윕 대상 밖 유령) 지금 수
SELECT job_name, triggered_by, count(*) FROM batch_execution WHERE status='RUNNING' GROUP BY 1,2;
