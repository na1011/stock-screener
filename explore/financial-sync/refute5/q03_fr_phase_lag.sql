-- #710을 셈(v5 · FR 10-30 → 11-25) 대 #683 기준(10-26 → 11-23) 대 즉시(10-08 → 11-03 → 11-30)
-- 모형: 2025년 같은 철에 「처음 공시된 라벨」(라벨 최소 published_at)을 2026 같은 월일로 옮겨, 그 뒤 첫 FR까지의 일수를 종목-라벨마다 더한다.
-- 현 편입(세 유니버스) · 창 2025-09-27 ~ 2025-11-29 · 다음 FR이 창 끝(12-21)을 넘으면 12-21까지만.
with cur as (select distinct m.stock_id, case when s.market_code='US' then 'US' else 'KR' end mkt
             from universe_membership m join stock s on s.id=m.stock_id
             where m.effective_to is null and m.universe_code in ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150')),
firsts as (select f.stock_id, f.fiscal_year, f.fiscal_period, min(f.published_at) p
           from financial_statement f join cur using (stock_id) group by 1,2,3),
w as (select cur.mkt, firsts.stock_id, (firsts.p + interval '1 year')::date p26
      from firsts join cur using (stock_id)
      where firsts.p between date '2025-09-27' and date '2025-11-29'),
ph(name, d) as (values
  ('P30_v5', date '2026-10-30'), ('P30_v5', date '2026-11-25'), ('P30_v5', date '2026-12-21'),
  ('P26_683', date '2026-10-26'), ('P26_683', date '2026-11-23'), ('P26_683', date '2026-12-21'),
  ('P08_now', date '2026-10-08'), ('P08_now', date '2026-11-03'), ('P08_now', date '2026-11-30'), ('P08_now', date '2026-12-21')),
lag as (select ph.name, w.mkt, w.stock_id, w.p26,
          (select min(d) from ph p2 where p2.name=ph.name and p2.d > w.p26) - w.p26 as lag
        from w cross join (select distinct name from ph) ph)
select name, mkt, count(*) labels, sum(lag) stock_label_days, round(avg(lag),1) avg_lag
from lag group by 1,2 order by 2,1;
