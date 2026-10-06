-- v13 결정 4 하류: 「그 시장 절반 이상이 D 봉을 가졌는데 편입 종목만 D 봉이 없다」를 지금(최종 상태) 기준으로 센다.
-- 그 구멍이 연속 몇 거래일 이어지는가(거래정지 꼴이면 그 동안 매일 「일시」 → 시장 D 판정이 그동안 멈춘다).
-- 편입 = 활성 유니버스 · 그날 구간 · 거래일 = indicator_trade_date(시장별). SELECT만.
set timezone='Asia/Seoul';
with mem as (
  select distinct m.stock_id, s.market_code, s.ticker, m.effective_from, m.effective_to
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
), md as (
  select c.market_code, c.trade_date d, mem.stock_id, mem.ticker,
         exists(select 1 from price_daily p where p.stock_id=mem.stock_id and p.trade_date=c.trade_date) has_bar
  from indicator_trade_date c join mem on mem.market_code=c.market_code
   and mem.effective_from<=c.trade_date and (mem.effective_to is null or mem.effective_to>c.trade_date)
  where c.trade_date >= date '2025-10-01'
), mk as (
  select market_code, d, count(*) n, count(*) filter (where has_bar) nb from md group by 1,2
), holes as (
  select md.market_code, md.d, md.stock_id, md.ticker from md join mk using (market_code,d)
  where not md.has_bar and mk.nb*2 >= mk.n
), seq as (
  select h.*, (select count(*) from indicator_trade_date c where c.market_code=h.market_code and c.trade_date<=h.d) tdn from holes h
), grp as (
  select market_code, stock_id, ticker, d, tdn - row_number() over (partition by stock_id order by d) g from seq
)
select market_code, ticker, min(d) first_d, max(d) last_d, count(*) days,
  exists(select 1 from price_daily p where p.stock_id=grp.stock_id and p.trade_date>max(grp.d)) later_bar
from grp group by market_code, stock_id, ticker, g order by days desc, market_code, first_d;
