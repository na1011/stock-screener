-- 최신일(10-02) FUNDAMENTAL 코드 수: SPGI·AOS(HOLE · 6월 분기는 있음) 대 같은 날 현 편입 미국 중앙값 · 그리고 SPGI·AOS에만 빠진 코드
with cur as (select m.stock_id from universe_membership m where m.universe_code='US_TOP1000' and m.effective_to is null),
fc as (select code from indicator where category='FUNDAMENTAL' and is_active),
n as (select v.stock_id, count(*) c from indicator_value v join cur using(stock_id) join fc on fc.code=v.indicator_code where v.trade_date='2026-10-02' group by 1)
select (select count(*) from fc) fund_codes, percentile_disc(0.5) within group (order by c) median,
  (select c from n join stock s on s.id=n.stock_id where s.ticker='SPGI' and s.market_code='US') spgi,
  (select c from n join stock s on s.id=n.stock_id where s.ticker='AOS' and s.market_code='US') aos,
  (select c from n join stock s on s.id=n.stock_id where s.ticker='ABT' and s.market_code='US') abt_tail
from n;
