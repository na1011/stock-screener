-- DART를 쓸 수 있는 잡 중 자정(KST)을 건넌 실행 — usedToday 차가 리셋을 건너는 실행
set timezone='Asia/Seoul';
select id, job_name, triggered_by, status, started_at::timestamp(0), finished_at::timestamp(0)
from batch_execution
where job_name in ('financial-refill','full-statement-refill','initial-load','corporate-action','governance-load','company-profile','universe-rebalance')
  and finished_at is not null
  and started_at::date <> finished_at::date
order by id;
select 'total DART-capable runs since 2026-08-01', count(*) from batch_execution
where job_name in ('financial-refill','full-statement-refill','initial-load','corporate-action','governance-load','company-profile','universe-rebalance') and started_at >= '2026-08-01';
select distinct job_name from batch_execution order by 1;
