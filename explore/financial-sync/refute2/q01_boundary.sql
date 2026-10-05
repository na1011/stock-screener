-- 경계 근처(10-01 종가 × 저장 주식수 · 기저 배수 · 기업행위 보정 생략) 종목의 주식수 기말 나이 — 현 편입 대 비편입
with latest as (
 select distinct on (f.stock_id) f.stock_id, f.shares_outstanding*coalesce(f.share_basis_multiple,1) sh, f.period_end, f.published_at
 from financial_statement f join stock s on s.id=f.stock_id
 where s.market_code='US' and f.published_at<=date '2026-10-02' and f.shares_outstanding>0
 order by f.stock_id, f.period_end desc, f.published_at desc, f.revision_no desc),
cap as (select l.*, l.sh*p.close cap from latest l join price_daily p on p.stock_id=l.stock_id and p.trade_date=date '2026-10-01'),
cur as (select distinct stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is null),
ever as (select distinct stock_id from universe_membership where universe_code='US_TOP1000')
select case when c.stock_id is not null then 'current' when e.stock_id is not null then 'past' else 'never' end grp,
  count(*) n,
  count(*) filter (where period_end < date '2026-05-01') shares_pre_may,
  count(*) filter (where period_end < date '2026-05-01' and published_at < date '2026-07-15') pre_may_and_old_pub
from cap left join cur c using(stock_id) left join ever e using(stock_id)
where cap between 5.1995e9*0.85 and 5.1995e9*1.15
group by 1 order by 1;
