-- governance-load 실행 이력(대상 수 큰 것)과 재편입 형제·현 대표의 거버넌스 원문 적재 시각
SELECT id, started_at::date, triggered_by, status, target_count, left(note,80) FROM batch_execution
WHERE job_name='governance-load' AND (target_count >= 100 OR started_at > '2026-09-25') ORDER BY started_at;
