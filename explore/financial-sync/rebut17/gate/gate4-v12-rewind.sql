-- 1부 v12 ADR-0173 0단계 4 글자(대상일 = 그 토요일 − 1 · 트리거 무관 · SUCCESS/PARTIAL · order by id desc limit 1)를
-- 지난 토요일 주변 시각으로 되감는다(SELECT만 · 그 시각까지 끝난 행만 · 명부는 created_at으로).
with ts as (
  select d::date as sat, x.lbl, (d::date + x.off) at time zone 'Asia/Seoul' as t
  from generate_series(date '2026-08-29', date '2026-10-03', interval '7 day') d
  cross join (values ('토13:00', interval '13 hours'), ('토19:59', interval '19 hours 59 minutes'),
                     ('토23:00', interval '23 hours'), ('일12:00', interval '36 hours')) x(lbl, off)
), g as (
  select ts.sat, ts.lbl, ts.t, r.id, r.triggered_by, r.status, r.started_at
  from ts left join lateral (
    select id, triggered_by, status, started_at from batch_execution
    where job_name = 'universe-rebalance' and target_date = ts.sat - 1 and status in ('SUCCESS', 'PARTIAL_SUCCESS')
      and finished_at < ts.t
    order by id desc limit 1) r on true
)
select g.sat, g.lbl, g.id, g.triggered_by, g.status, to_char(g.started_at, 'MM-DD HH24:MI') as started,
       (select max(effective_from) from universe_membership m where m.universe_code = 'US_TOP1000' and m.created_at < g.t) as us_max_eff,
       (select count(*) from universe_membership m where m.universe_code='US_TOP1000' and m.effective_from = g.sat - 1 and m.created_at < g.t) as us_new_asof,
       (select count(*) from universe_membership m where m.universe_code='US_TOP1000' and m.effective_from = g.sat - 1) as us_new_final,
       (select string_agg(id || ':' || triggered_by || ':' || status || '@' || to_char(started_at,'MM-DD HH24:MI'), ' ' order by id)
          from batch_execution b where b.job_name='universe-rebalance' and b.target_date = g.sat - 1 and b.started_at >= g.t) as later_same_target
from g order by g.sat, g.t;
