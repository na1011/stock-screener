-- 결정 4 「그 시장 절반 이상이 D 봉을 가졌는데 편입 종목만 D 봉이 없다 → PRICE 실패 행」을 정기 일일 갱신 실행 시각(finished_at)으로 재생.
-- 시장 D = PriceCalendar.latestTradeDateOnOrBefore 꼴(그 시각까지 적재된 봉의 max ≤ target) — 실행 끝 시각 기준.
-- 편입은 두 판: (now) 지금 표 · (asof) created_at ≤ f 인 행만(소급 RESTORED 행 제외 효과).
set timezone='Asia/Seoul'; set statement_timeout=0;
with runs as (
  select id, target_date t, finished_at f from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS')
    and target_date >= date '2026-08-31'
), mk as (select distinct market_code from stock where market_code in ('KOSPI','KOSDAQ','US')),
dmk as (
  select r.id, r.t, r.f, mk.market_code,
    (select max(p.trade_date) from price_daily p join stock s on s.id=p.stock_id
      where s.market_code=mk.market_code and p.trade_date<=r.t and p.trade_date>=r.t-14 and p.created_at<=r.f) d
  from runs r cross join mk
), mem as (
  select dmk.id, dmk.market_code, dmk.d, dmk.t, dmk.f, m.stock_id, bool_or(m.created_at<=dmk.f) asof_member
  from dmk join universe_membership m on m.effective_from<=dmk.d and (m.effective_to is null or m.effective_to>dmk.d)
  join universe u on u.code=m.universe_code and u.is_active
  join stock s on s.id=m.stock_id and s.market_code=dmk.market_code
  group by 1,2,3,4,5,6
), cov as (
  select mem.*, exists(select 1 from price_daily p where p.stock_id=mem.stock_id and p.trade_date=mem.d and p.created_at<=mem.f) has_d
  from mem
)
select id, t, market_code, d, (d<>t) d_ne_t,
  count(*) members_now, count(*) filter (where asof_member) members_asof,
  count(*) filter (where has_d) have_now,
  count(*) filter (where not has_d) missing_now,
  count(*) filter (where not has_d and asof_member) missing_asof,
  round(100.0*count(*) filter (where has_d)/count(*),1) pct_have
from cov group by 1,2,3,4 order by 2,3;
