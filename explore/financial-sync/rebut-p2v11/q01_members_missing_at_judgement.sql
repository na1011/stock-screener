-- 결정 5 「시장 D = 원본 전원 + 파생층이 끝난 날」: SCHEDULER daily-update(대상일 D)가 끝난 시각 F에
-- 그 D에 편입이던 종목 중 D 봉이 아직 없던 것(나중에 메워졌거나 끝내 없음) — 시장별
-- 「원본 전원」을 ⑦②의 표지로 갈음하면 이 종목들이 빠진 모집단으로 D의 백분위가 나와 ⑧이 판정한다.
set timezone='Asia/Seoul';
with runs as (
  select id, target_date d, finished_at f from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS','PARTIAL')
    and target_date >= '2026-08-10'
), mem as (
  select distinct m.stock_id, s.market_code, m.effective_from, m.effective_to
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code
), cal as (select market_code, trade_date from indicator_trade_date)
select r.d, mem.market_code,
       count(*) filter (where p.stock_id is null) never_bar,
       count(*) filter (where p.created_at > r.f) late_bar,
       count(*) members
from runs r
join cal on cal.trade_date = r.d
join mem on mem.market_code = cal.market_code and mem.effective_from <= r.d and (mem.effective_to is null or mem.effective_to > r.d)
left join price_daily p on p.stock_id = mem.stock_id and p.trade_date = r.d
group by 1,2
having count(*) filter (where p.stock_id is null) + count(*) filter (where p.created_at > r.f) > 0
order by 1,2;
