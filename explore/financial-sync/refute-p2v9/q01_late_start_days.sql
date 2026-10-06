-- 평일마다 SCHEDULER 잡이 KST 몇 시에 처음 시작했나 — 13:30 일일 갱신 없이 뒤 크론만 돈 날(= 13:30 뒤 기동한 날)을 센다.
-- 모집단: corporate-action SCHEDULER가 처음 생긴 09-02 이후 평일(KST).
with s as (
  select (started_at at time zone 'Asia/Seoul') as kst, job_name, triggered_by, status, id
  from batch_execution where triggered_by='SCHEDULER'
), d as (
  select g::date as day from generate_series('2026-09-02'::date, '2026-10-06'::date, '1 day') g
  where extract(isodow from g) between 1 and 5
)
select d.day,
  (select min(kst)::time from s where kst::date=d.day and job_name='daily-update') as du,
  (select min(kst)::time from s where kst::date=d.day and job_name='corporate-action') as ca,
  (select string_agg(distinct job_name||'@'||to_char(kst,'HH24:MI'),' ' ) from s where kst::date=d.day and job_name not in ('daily-update','corporate-action','adjustment-rebuild')) as others
from d order by d.day;
