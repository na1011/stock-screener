-- q05 정정: q05는 「뒤 날짜에 ADJ_CLOSE가 있는」 구멍만 셌다 → 편출로 수집이 멎은 종목의 편입 구간 끝 구멍을 원리적으로 못 본다.
-- 여기선 조건 없이: 활성 유니버스 편입 구간 안 · 그 시장 지표 달력 날짜 D · D에 ADJ_CLOSE 없음 · D < 최신 달력일(프런티어 제외).
-- 지금 is_active 여부와 무관(편출 종목 포함). 종목별로 연다.
set timezone='Asia/Seoul';
with mem as (
  select m.stock_id, s.market_code, s.ticker, m.universe_code, m.effective_from f, m.effective_to t
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
), lastd as (select market_code, max(trade_date) mx from indicator_trade_date group by 1),
holes as (
  select mem.market_code, mem.ticker, mem.stock_id, mem.universe_code, mem.f, mem.t, d.trade_date
  from mem join indicator_trade_date d on d.market_code=mem.market_code and d.trade_date>=mem.f and (mem.t is null or d.trade_date<mem.t)
  join lastd l on l.market_code=mem.market_code and d.trade_date < l.mx
  where not exists (select 1 from indicator_value iv where iv.stock_id=mem.stock_id and iv.trade_date=d.trade_date and iv.indicator_code='ADJ_CLOSE')
)
select market_code, ticker, universe_code, f, t, count(*) hole_days, min(trade_date) first_hole, max(trade_date) last_hole,
  (select max(iv.trade_date) from indicator_value iv where iv.stock_id=holes.stock_id and iv.indicator_code='ADJ_CLOSE') last_adj
from holes group by 1,2,3,4,5,holes.stock_id order by 1,2;
