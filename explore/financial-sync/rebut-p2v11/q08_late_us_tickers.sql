-- q07의 US 늦은 봉(시장 재적재 제외: 그 실행·시장 늦은 비율 < 50%)을 종목으로 연다 + 그 실행의 실패 행 유무
set timezone='Asia/Seoul';
with runs as (
  select id, target_date d, finished_at f, failure_count fc from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS') and finished_at is not null
), mem as (
  select distinct m.stock_id, s.market_code, s.ticker, m.effective_from, m.effective_to
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
  where s.market_code='US'
), x as (
  select r.id, r.d, r.fc, mem.ticker, mem.stock_id, p.created_at c, r.f
  from runs r join indicator_trade_date cal on cal.trade_date=r.d and cal.market_code='US'
  join mem on mem.effective_from<=r.d and (mem.effective_to is null or mem.effective_to>r.d)
  left join price_daily p on p.stock_id=mem.stock_id and p.trade_date=r.d
)
select x.id, x.d, x.fc run_failures, x.ticker, to_char(x.c,'MM-DD HH24:MI') arrived,
  exists(select 1 from batch_execution_failure bf where bf.execution_id=x.id and bf.stock_id=x.stock_id) has_failure_row
from x where (x.c is null or x.c > x.f)
  and (select count(*) from x y where y.id=x.id and y.c > y.f) < 500
order by x.d, x.ticker;
