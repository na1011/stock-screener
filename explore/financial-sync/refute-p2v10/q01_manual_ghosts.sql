-- MANUAL(bootRun) 실행 중 「프로세스가 사라져」 스위퍼가 닫은 행(유령) — v10 결정 2 「MANUAL DART 잡이 RUNNING이면 단계를 미룬다」가
-- 유령 행에 걸리는 시간(started_at → 스위퍼 마감 finished_at) · DART를 쓸 수 있는 잡 여부 · 지금 RUNNING인 MANUAL
select id, job_name, triggered_by, status,
       to_char(started_at at time zone 'Asia/Seoul','MM-DD HH24:MI') st,
       to_char(coalesce(progress_at,started_at) at time zone 'Asia/Seoul','MM-DD HH24:MI') last_progress,
       to_char(finished_at at time zone 'Asia/Seoul','MM-DD HH24:MI') fin,
       round(extract(epoch from (finished_at - coalesce(progress_at,started_at)))/3600.0,1) ghost_h,
       job_name in ('financial-refill','full-statement-refill','governance-load','corporate-action','company-profile','initial-load') dart_capable
from batch_execution
where triggered_by='MANUAL' and (note like '%프로세스가 사라져%' or status='RUNNING')
order by id;
