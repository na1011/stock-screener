-- 관리자 경로(ADMIN · AdminJobLauncher의 taskExecutor)와 스케줄러 풀(SCHEDULER·GAP_FILL)이 같은 JVM에서 시간상 겹친 실행 —
-- v10 결정 4 「관리자 경로도 끝에 같은 ⑧ 루틴을 부른다 — 워터마크가 중복을 막는다」가 동시 실행에서 서는가의 모집단
select a.id admin_id, a.job_name admin_job,
       to_char(a.started_at at time zone 'Asia/Seoul','MM-DD HH24:MI') a_st, to_char(a.finished_at at time zone 'Asia/Seoul','HH24:MI') a_fin,
       s.id sched_id, s.job_name sched_job, s.triggered_by,
       to_char(s.started_at at time zone 'Asia/Seoul','HH24:MI') s_st, to_char(s.finished_at at time zone 'Asia/Seoul','HH24:MI') s_fin
from batch_execution a join batch_execution s
  on s.triggered_by in ('SCHEDULER','GAP_FILL') and a.triggered_by='ADMIN'
 and s.started_at < coalesce(a.finished_at, now()) and a.started_at < coalesce(s.finished_at, now())
order by a.id, s.id;
select count(*) admin_runs, count(*) filter (where job_name='daily-update') admin_daily_update from batch_execution where triggered_by='ADMIN';
