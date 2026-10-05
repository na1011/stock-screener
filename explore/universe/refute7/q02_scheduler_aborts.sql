-- 웹 JVM이 정기 실행 중 내려간 빈도: SCHEDULER·GAP_FILL 행의 상태별 수와 ABORTED note 형태
SET statement_timeout='60s';
SELECT triggered_by, status, count(*) n, min(started_at)::date first, max(started_at)::date last
FROM batch_execution WHERE triggered_by IN ('SCHEDULER','GAP_FILL','ADMIN') GROUP BY 1,2 ORDER BY 1,2;
SELECT id, job_name, triggered_by, started_at AT TIME ZONE 'Asia/Seoul' kst, round(extract(epoch FROM finished_at-started_at)) s, left(note,120) note
FROM batch_execution WHERE triggered_by IN ('SCHEDULER','GAP_FILL','ADMIN') AND status NOT IN ('SUCCESS','FAILED') ORDER BY started_at;
