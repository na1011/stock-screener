SET statement_timeout='60s';
-- FR·FSR 실행 전수: 트리거 · 상태 · 대상 · 실패 · note 앞부분 (09-14 이후)
SELECT id, job_name, triggered_by, status, to_char(started_at AT TIME ZONE 'Asia/Seoul','MM-DD HH24:MI') st,
       to_char(finished_at AT TIME ZONE 'Asia/Seoul','MM-DD HH24:MI') fin, target_count tc, success_count sc, failure_count fc,
       left(note, 120) note
FROM batch_execution WHERE job_name IN ('financial-refill','full-statement-refill') AND started_at > '2026-09-14'
ORDER BY id;
-- GAP_FILL로 남은 FR·FSR 실행 전체 이력
SELECT job_name, triggered_by, count(*), min(started_at)::date, max(started_at)::date FROM batch_execution
WHERE job_name IN ('financial-refill','full-statement-refill') GROUP BY 1,2 ORDER BY 1,2;
