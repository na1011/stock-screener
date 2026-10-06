-- 평일 SCHEDULER 잡별 소요(최근 60일) · 상태 · 시작 시각
select job_name, count(*) n,
       round(percentile_cont(0.5) within group (order by extract(epoch from finished_at-started_at))::numeric/60,1) med_min,
       round(percentile_cont(0.9) within group (order by extract(epoch from finished_at-started_at))::numeric/60,1) p90_min,
       round(max(extract(epoch from finished_at-started_at))::numeric/60,1) max_min,
       min(started_at)::date first_d, max(started_at)::date last_d
from batch_execution
where triggered_by='SCHEDULER' and finished_at is not null and started_at > now()-interval '60 days'
group by job_name order by job_name;
