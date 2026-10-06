-- 60일 batch_execution 겹침 쌍(트리거 짝별) · 운영 SELECT만
with r as (
  select id, job_name, triggered_by tb, status, started_at s, coalesce(finished_at, progress_at, started_at) f
  from batch_execution where started_at > now() - interval '60 days')
select a.tb||'>'||b.tb as pair, count(*) pairs, count(distinct (a.s at time zone 'Asia/Seoul')::date) days,
       string_agg(a.id||'('||a.job_name||')~'||b.id||'('||b.job_name||')', ' ' order by a.id) filter (where true) as sample
from r a join r b on a.id < b.id and b.s < a.f and a.s < b.f
group by 1 order by 2 desc;
