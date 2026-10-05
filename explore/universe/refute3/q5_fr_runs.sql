-- financial-refill 실행 이력 최근 15 · 트리거 · 대상 수 · 실패 수 · 소요
SELECT id, triggered_by, status, target_count, success_count, failure_count, started_at::timestamp(0), (finished_at-started_at)::interval(0) dur, left(note,160) note
FROM batch_execution WHERE job_name='financial-refill' ORDER BY id DESC LIMIT 15;
