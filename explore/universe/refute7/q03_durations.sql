-- 14:17 상한(FR 150 · FSR 300) 아래 최악 소요를 실측 실행의 종목당 초로 환산
SET statement_timeout='60s';
SELECT id, job_name, triggered_by, status, target_count, success_count, failure_count,
       round(extract(epoch FROM finished_at-started_at)) s,
       round((extract(epoch FROM finished_at-started_at)/nullif(target_count,0))::numeric,2) s_per_stock,
       started_at AT TIME ZONE 'Asia/Seoul' kst, left(note,140) note
FROM batch_execution WHERE id IN (645,646,648,658,659,663,683,706,710) ORDER BY id;
-- 정기 화해·유도·사슬의 최근 소요(14:18·14:20 밀림 계산용)
SELECT job_name, triggered_by, count(*) n, round(max(extract(epoch FROM finished_at-started_at))) max_s, round(avg(extract(epoch FROM finished_at-started_at))) avg_s
FROM batch_execution WHERE started_at > now() - interval '10 days' AND triggered_by IN ('SCHEDULER','GAP_FILL') GROUP BY 1,2 ORDER BY 1;
