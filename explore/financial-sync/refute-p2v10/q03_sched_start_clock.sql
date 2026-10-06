-- SCHEDULER 일일 갱신(13:30 크론)의 실제 시작 시각 분포 — 수면으로 자정을 넘겨 13:00(소스 공개) 전에 돈 날이 있었나
-- (v10 문 「오늘 이 사슬이 시작됐나」가 그 이른 발화를 그날의 유일한 사슬로 만드는 갈래의 실측 빈도)
select (started_at at time zone 'Asia/Seoul')::date d,
       to_char(started_at at time zone 'Asia/Seoul','Dy HH24:MI:SS') st,
       target_date, status
from batch_execution
where job_name='daily-update' and triggered_by='SCHEDULER'
  and ((started_at at time zone 'Asia/Seoul')::time < '13:29:00' or (started_at at time zone 'Asia/Seoul')::time > '13:35:00'
       or extract(isodow from started_at at time zone 'Asia/Seoul') >= 6)
order by 1;
select count(*) total_sched_daily_update,
       min((started_at at time zone 'Asia/Seoul')::date) first_d,
       count(*) filter (where (started_at at time zone 'Asia/Seoul')::time < '13:00') before_13
from batch_execution where job_name='daily-update' and triggered_by='SCHEDULER';
