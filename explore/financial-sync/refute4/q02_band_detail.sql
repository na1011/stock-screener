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
cur as (select distinct stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is null)
select (cur.stock_id is not null) is_member, count(*) n_active_with_shares, count(px.stock_id) with_recent_price
from stock s join sh on sh.stock_id=s.id left join px on px.stock_id=s.id left join cur on cur.stock_id=s.id
where s.market_code='US' and s.is_active group by 1;
