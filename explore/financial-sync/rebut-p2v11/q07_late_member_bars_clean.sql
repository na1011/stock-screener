-- M1 재측: SCHEDULER daily-update(대상일 d)가 끝난 시각 f에 d 편입(활성 유니버스) 종목 중 d 봉이 없던 것.
-- 「없던 것」 = 지금도 없음(never) 또는 created_at > f(나중에 들어옴). 시장 전체 재적재(늦은 비율 50% 이상)는 따로 표시.
set timezone='Asia/Seoul';
with runs as (
  select id, status, target_date d, finished_at f from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS') and finished_at is not null
), mem as (
  select distinct m.stock_id, s.market_code, m.effective_from, m.effective_to
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
), x as (
  select r.id, r.status, r.d, mem.market_code, mem.stock_id, p.created_at c, r.f
  from runs r join indicator_trade_date cal on cal.trade_date=r.d
  join mem on mem.market_code=cal.market_code and mem.effective_from<=r.d and (mem.effective_to is null or mem.effective_to>r.d)
  left join price_daily p on p.stock_id=mem.stock_id and p.trade_date=r.d
)
select id, status, d, market_code, count(*) members,
  count(*) filter (where c is null) never,
  count(*) filter (where c > f) late,
  round(100.0*count(*) filter (where c > f)/count(*),1) late_pct,
  min(c) filter (where c > f) first_late
from x group by 1,2,3,4
having count(*) filter (where c is null or c > f) > 0
order by 3,4;
