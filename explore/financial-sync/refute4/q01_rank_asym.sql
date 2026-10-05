-- 10-10 리밸런싱 비대칭 근사: 최신 종가 × 최신 저장 주식수(is_latest·published_at 최대)로 미국 활성 순위,
-- 경계(1000위) ±15% 띠 안에서 「작년 같은 창에 공시가 있었던」 종목 수(현 편입: 09-27~10-08 · 후보: 08-29~10-08).
with px as (
  select distinct on (p.stock_id) p.stock_id, p.close, p.trade_date
  from price_daily p join stock s on s.id=p.stock_id
  where s.market_code='US' and s.is_active and p.trade_date >= date '2026-09-25'
  order by p.stock_id, p.trade_date desc),
sh as (
  select distinct on (f.stock_id) f.stock_id, f.shares_outstanding, f.published_at
  from financial_statement f join stock s on s.id=f.stock_id
  where s.market_code='US' and f.is_latest and f.shares_outstanding is not null and f.shares_outstanding > 0
  order by f.stock_id, f.published_at desc, f.period_end desc),
cur as (select distinct stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is null),
mc as (
  select s.id, s.ticker, px.close*sh.shares_outstanding mcap, sh.published_at last_pub, (cur.stock_id is not null) is_member,
         rank() over (order by px.close*sh.shares_outstanding desc) rk
  from stock s join px on px.stock_id=s.id join sh on sh.stock_id=s.id left join cur on cur.stock_id=s.id
  where s.market_code='US' and s.is_active),
b as (select mcap boundary from mc where rk=1000),
band as (select mc.*, mc.mcap/b.boundary ratio from mc, b where mc.mcap between b.boundary*0.85 and b.boundary*1.15)
select is_member,
       count(*) n_band,
       count(*) filter (where exists (select 1 from financial_statement f where f.stock_id=band.id and f.published_at between date '2025-09-27' and date '2025-10-08')) pub_lastyr_0927_1008,
       count(*) filter (where exists (select 1 from financial_statement f where f.stock_id=band.id and f.published_at between date '2025-08-29' and date '2025-10-08')) pub_lastyr_0829_1008,
       min(last_pub) min_last_pub, max(last_pub) max_last_pub
from band group by is_member order by 1;
