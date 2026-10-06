-- 결정 5 보류 원인 (ii) 「D에 편입인데 ADJ_CLOSE 없음 = 일시 구멍」: 지금 걸리는 (종목, D)가 일시인가 영구인가(뒤 날짜 ADJ_CLOSE 유무 · 봉 유무)
set timezone='Asia/Seoul';
with mem as (
  select m.stock_id, s.market_code, s.ticker, min(m.effective_from) f
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
  where m.effective_to is null and s.is_active group by 1,2,3
), d as (select t.market_code, t.trade_date from indicator_trade_date t where t.trade_date >= '2026-08-10')
select mem.ticker, d.trade_date,
       exists(select 1 from price_daily p where p.stock_id=mem.stock_id and p.trade_date=d.trade_date) has_bar,
       (select max(iv.trade_date) from indicator_value iv where iv.stock_id=mem.stock_id and iv.indicator_code='ADJ_CLOSE') last_adj,
       (select count(*) from indicator_value iv where iv.stock_id=mem.stock_id and iv.indicator_code='ADJ_CLOSE' and iv.trade_date > d.trade_date) adj_after
from mem join d on d.market_code = mem.market_code and d.trade_date >= mem.f
where not exists (select 1 from indicator_value iv where iv.stock_id = mem.stock_id and iv.trade_date = d.trade_date and iv.indicator_code = 'ADJ_CLOSE')
order by 1,2;
