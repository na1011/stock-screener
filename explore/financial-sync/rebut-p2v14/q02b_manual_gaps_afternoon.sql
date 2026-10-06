set timezone='Asia/Seoul';
with m as (
  select id, job_name, started_at, finished_at,
         lag(finished_at) over (order by started_at) prev_fin, lag(job_name) over (order by started_at) prev_job
  from batch_execution where triggered_by in ('MANUAL','ADMIN') and started_at > now() - interval '60 days'
), g as (select *, extract(epoch from started_at - prev_fin) gap_s from m where prev_fin is not null)
select to_char(started_at,'MM-DD Dy') d, count(*) pairs_lt120s, min(to_char(started_at,'HH24:MI')) first_, max(to_char(started_at,'HH24:MI')) last_,
       string_agg(distinct prev_job||'>'||job_name, ' ' ) filter (where prev_job<>job_name) transitions
from g where gap_s between 0 and 120 and extract(isodow from started_at) between 1 and 5
  and extract(hour from started_at) between 13 and 22 group by 1 order by 1;
