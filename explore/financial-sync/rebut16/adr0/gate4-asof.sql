-- 0단계 4(N=2 리밸런싱 관문)를 과거 토요일 낮 13:00 KST 시점으로 되감아 두 읽기로 평가한다(SELECT만 · created_at으로 그 시점의 명부).
-- natural  = 「그 리밸런싱의 SCHEDULER 행」을 그 시점의 마지막 SCHEDULER universe-rebalance 행으로 읽음
-- anchored = target_date를 그 토요일 전날(금)로 박음(v10 계획 밖 반증 2의 처방 꼴)
with ts as (
  select d::date as sat, (d::date + time '13:00') at time zone 'Asia/Seoul' as t
  from generate_series(date '2026-08-29', date '2026-10-03', interval '7 day') d
), nat as (
  select ts.sat, ts.t, r.id, r.target_date, r.status
  from ts left join lateral (
    select id, target_date, status from batch_execution
    where job_name='universe-rebalance' and triggered_by='SCHEDULER' and started_at < ts.t
    order by started_at desc limit 1) r on true
), anc as (
  select ts.sat, r.id, r.status from ts left join lateral (
    select id, status from batch_execution
    where job_name='universe-rebalance' and triggered_by='SCHEDULER' and started_at < ts.t
      and target_date = ts.sat - 1 order by started_at desc limit 1) r on true
)
select n.sat, n.id as nat_row, n.target_date as nat_target, n.status as nat_status,
       (select max(effective_from) from universe_membership m
         where m.universe_code='US_TOP1000' and m.created_at < n.t) as max_eff_asof,
       (n.status='SUCCESS' and n.target_date = (select max(effective_from) from universe_membership m
         where m.universe_code='US_TOP1000' and m.created_at < n.t)) as natural_passes,
       a.id is not null and a.status='SUCCESS' as anchored_passes
from nat n join anc a using (sat) order by n.sat;
