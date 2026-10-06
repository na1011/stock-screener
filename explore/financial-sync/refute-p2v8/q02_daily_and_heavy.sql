-- 일일 갱신(SCHEDULER) 실행별 시작·소요 · 무거운 잡 셋의 전량 실행(모든 트리거) 소요
select id, job_name, triggered_by, status, target_count,
       to_char(started_at at time zone 'Asia/Seoul','MM-DD HH24:MI:SS') started_kst,
       to_char(finished_at-started_at,'HH24:MI:SS') dur
from batch_execution
where (job_name='daily-update' and triggered_by='SCHEDULER' and started_at > '2026-08-31')
   or (job_name in ('governance-load','full-statement-refill','financial-refill','corporate-action') and target_count >= 300 and started_at > '2026-09-01')
order by job_name, started_at;
