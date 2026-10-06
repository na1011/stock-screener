-- DART를 쓸 수 있는 잡의 끝 상태 — ABORTED·FAILED는 끝에 note를 쓰는 경로를 안 탈 수 있다(콜 수가 합에서 빠지는 후보)
set timezone='Asia/Seoul';
select job_name, triggered_by, status, count(*), min(started_at)::date, max(started_at)::date,
       sum(case when extract(isodow from started_at) <= 5 then 1 else 0 end) weekday_runs
from batch_execution
where job_name in ('financial-refill','full-statement-refill','initial-load','corporate-action','governance-load','company-profile','universe-rebalance')
  and started_at >= '2026-08-10'
group by 1,2,3 order by 1,2,3;
