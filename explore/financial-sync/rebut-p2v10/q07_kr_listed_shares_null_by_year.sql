-- 결정 6 「국내는 봉에 listed_shares가 있어 sharesOf가 재무를 안 읽는다」 — 09-01 뒤만 잰 수였다. 창 전체(연도별)로.
-- 주식수 넷 중 SHARES_GROWTH_3Y·TURNOVER_RATIO_60은 과거 봉의 주식수를 읽는다(3년 전 · 60거래일).
select s.market_code, extract(year from p.trade_date)::int yr, count(*) bars,
       count(*) filter (where p.listed_shares is null) null_shares,
       round(100.0*count(*) filter (where p.listed_shares is null)/count(*),2) pct
from price_daily p join stock s on s.id=p.stock_id
where s.market_code in ('KOSPI','KOSDAQ')
group by 1,2 order by 1,2;
