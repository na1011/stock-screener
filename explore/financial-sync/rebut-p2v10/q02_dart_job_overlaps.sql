-- DART를 쓸 수 있는 잡끼리 시간이 겹친 쌍 — 같은 JVM 쌍(앱 트리거끼리)이면 usedToday 차가 서로를 센다
set timezone='Asia/Seoul';
with j as (
  select id, job_name, triggered_by, started_at, coalesce(finished_at, now()) fin, status
  from batch_execution
  where job_name in ('financial-refill','full-statement-refill','initial-load','corporate-action','governance-load','company-profile','universe-rebalance')
    and started_at >= '2026-08-10' and status <> 'ABORTED'
)
select a.id, a.job_name, a.triggered_by, b.id, b.job_name, b.triggered_by,
       greatest(a.started_at,b.started_at)::timestamp(0) ov_from, least(a.fin,b.fin)::timestamp(0) ov_to,
       case when a.triggered_by<>'MANUAL' and b.triggered_by<>'MANUAL' then 'same-app-jvm'
            when a.triggered_by='MANUAL' and b.triggered_by='MANUAL' then 'manual-manual'
            else 'cross-jvm' end kind
from j a join j b on a.id < b.id and a.started_at < b.fin and b.started_at < a.fin
order by a.id;
