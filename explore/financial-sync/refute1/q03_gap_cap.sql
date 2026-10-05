-- gap80의 10-02 시총(저장 주식수 × 10-02 종가 · 기저 배수 반영 · 기업행위 보정 생략) 과 경계(1000위 ≈ 5.20e9) 대비
with g as (select unnest(string_to_array(:'lst', ',')) t),
latest as (
 select distinct on (f.stock_id) f.stock_id, f.shares_outstanding*coalesce(f.share_basis_multiple,1) sh, f.period_end, f.published_at
 from financial_statement f join stock s on s.id=f.stock_id join g on g.t=s.ticker
 where s.market_code='US' and f.published_at<=date '2026-10-02' and f.shares_outstanding>0
 order by f.stock_id, f.period_end desc, f.published_at desc, f.revision_no desc)
select s.ticker, l.period_end, round(l.sh*p.close/1e9,2) cap_b, round(l.sh*p.close/5.1995e9,3) ratio_to_cut
from latest l join stock s on s.id=l.stock_id join price_daily p on p.stock_id=l.stock_id and p.trade_date=date '2026-10-01'
order by abs(ln(l.sh*p.close/5.1995e9)) limit 12;
