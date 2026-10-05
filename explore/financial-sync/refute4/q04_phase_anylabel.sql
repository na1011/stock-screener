-- 라벨 무관: 2025-10~11에 「처음 공시된 라벨」(그 라벨의 최소 published_at)을 가진 현 편입 종목을 창별로 센다.
with cur as (select distinct m.stock_id, case when s.market_code='US' then 'US' else 'KR' end mkt from universe_membership m join stock s on s.id=m.stock_id
             where m.effective_to is null and m.universe_code in ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150')),
firsts as (select f.stock_id, f.fiscal_year, f.fiscal_period, min(f.published_at) p from financial_statement f join cur using (stock_id) group by 1,2,3),
w as (select cur.mkt, firsts.stock_id, firsts.p from firsts join cur using (stock_id) where firsts.p between date '2025-09-27' and date '2025-12-01')
select mkt,
  count(distinct stock_id) filter (where p between date '2025-09-27' and date '2025-10-07') "0927_1007(신:10-08 · 구:10-30)",
  count(distinct stock_id) filter (where p between date '2025-10-08' and date '2025-10-29') "1008_1029(신:11-03 · 구:10-30)",
  count(distinct stock_id) filter (where p between date '2025-10-30' and date '2025-11-02') "1030_1102(신:11-03 · 구:11-25)",
  count(distinct stock_id) filter (where p between date '2025-11-03' and date '2025-11-24') "1103_1124(신:11-30 · 구:11-25)",
  count(distinct stock_id) filter (where p between date '2025-11-25' and date '2025-11-29') "1125_1129(신:11-30 · 구:다음)"
from w group by 1 order by 1;
