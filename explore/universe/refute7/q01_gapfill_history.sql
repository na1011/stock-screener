-- 14:17이 매 평일 만들 GAP_FILL 실행의 기존 노출: GAP_FILL 잡별 실행 수·소요, 그리고 스위퍼가 닫은 고아(크래시 빈도)
SET statement_timeout='60s';
SELECT job_name, triggered_by, status, count(*) n,
       round(avg(extract(epoch FROM finished_at-started_at))::numeric,1) avg_s,
       round(max(extract(epoch FROM finished_at-started_at))::numeric,1) max_s,
       min(started_at)::date first, max(started_at)::date last
FROM batch_execution WHERE triggered_by='GAP_FILL' GROUP BY 1,2,3 ORDER BY 1,3;
-- 프로세스가 사라져 닫힌 행(스위퍼 note) — 트리거·월별
SELECT triggered_by, to_char(started_at,'YYYY-MM') ym, count(*) n,
       count(*) FILTER (WHERE extract(isodow FROM started_at AT TIME ZONE 'Asia/Seoul')<6
                         AND (started_at AT TIME ZONE 'Asia/Seoul')::time BETWEEN '13:30' AND '15:45') in_weekday_window
FROM batch_execution WHERE note LIKE '%프로세스가 사라져%' GROUP BY 1,2 ORDER BY 2,1;
-- 지금 RUNNING
SELECT id, job_name, triggered_by, started_at, progress_at FROM batch_execution WHERE status='RUNNING';
