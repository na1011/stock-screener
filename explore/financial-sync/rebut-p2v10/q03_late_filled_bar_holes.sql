-- 「일시 구멍」이 순서를 뒤집는 꼴: 편입 종목 S의 봉 D가 다음 봉 D'보다 늦게 들어온 (S, D)
-- (D'가 정상 흐름 — D'의 다음 날 이틀 안 — 으로 들어왔고 D는 그 뒤에 메워졌다 → D' 판정 시점에 D가 비어 있었다)
set timezone='Asia/Seoul';
with b as (
  select p.stock_id, s.market_code, s.ticker, p.trade_date, p.created_at,
         lead(p.trade_date) over w nd, lead(p.created_at) over w nc
  from price_daily p join stock s on s.id = p.stock_id
  where p.trade_date >= '2026-08-03'
  window w as (partition by p.stock_id order by p.trade_date)
), m as (
  select distinct stock_id from universe_membership where effective_to is null
)
select b.market_code, count(*) holes, count(distinct b.stock_id) stocks,
       min(b.trade_date) first_d, max(b.trade_date) last_d
from b join m using (stock_id)
where b.nd is not null
  and b.created_at > b.nc                              -- D가 D'보다 늦게 들어옴
  and b.nc < (b.nd + 3)::timestamptz + interval '15 hours'  -- D'는 정상 흐름(그다음 사흘 안 15시 전)
  and b.trade_date >= '2026-08-10'
group by 1 order by 1;
-- 예시 20
with b as (
  select p.stock_id, s.market_code, s.ticker, p.trade_date, p.created_at,
         lead(p.trade_date) over w nd, lead(p.created_at) over w nc
  from price_daily p join stock s on s.id = p.stock_id
  where p.trade_date >= '2026-08-03'
  window w as (partition by p.stock_id order by p.trade_date)
), m as (select distinct stock_id from universe_membership where effective_to is null)
select b.market_code, b.ticker, b.trade_date, b.created_at::timestamp(0) d_created, b.nd, b.nc::timestamp(0) next_created
from b join m using (stock_id)
where b.nd is not null and b.created_at > b.nc and b.nc < (b.nd + 3)::timestamptz + interval '15 hours' and b.trade_date >= '2026-08-10'
order by b.trade_date, b.ticker limit 20;
