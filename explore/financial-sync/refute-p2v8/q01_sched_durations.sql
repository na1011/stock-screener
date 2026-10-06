-- SCHEDULER 실행 소요(잡별 · 최근 60일) — 운영 DB SELECT만
select job_name, triggered_by, count(*) n,
       to_char(min(started_at at time zone 'Asia/Seoul'),'MM-DD') first,
       to_char(max(started_at at time zone 'Asia/Seoul'),'MM-DD') last,
       justify_interval(percentile_cont(0.5) within group (order by finished_at-started_at)) p50,
       justify_interval(max(finished_at-started_at)) max
from batch_execution
where started_at > now() - interval '60 days' and finished_at is not null
  and triggered_by in ('SCHEDULER','GAP_FILL')
group by 1,2 order by 1,2;
