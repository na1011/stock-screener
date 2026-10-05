-- v5 위상(FR 10-30 → 11-25 → 12-21)에서 2부(14:17 동기 · 다음 평일 반영 근사 = 공시 다음 날)가 X일에 켜지면 줄어드는 라벨-일.
-- 모형 = q03과 같음(2025 같은 철 라벨 첫 공시를 2026으로 옮김 · 현 편입 · 2025-09-27~11-29). FSR(국내 현금흐름·분기 칸)은 안 셈 — 하한.
with cur as (select distinct m.stock_id, case when s.market_code='US' then 'US' else 'KR' end mkt
             from universe_membership m join stock s on s.id=m.stock_id
             where m.effective_to is null and m.universe_code in ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150')),
firsts as (select f.stock_id, f.fiscal_year, f.fiscal_period, min(f.published_at) p
           from financial_statement f join cur using (stock_id) group by 1,2,3),
w as (select cur.mkt, (firsts.p + interval '1 year')::date p26
      from firsts join cur using (stock_id) where firsts.p between date '2025-09-27' and date '2025-11-29'),
fr(d) as (values (date '2026-10-30'), (date '2026-11-25'), (date '2026-12-21')),
x(xd) as (values (date '2026-10-09'), (date '2026-10-16'), (date '2026-10-23'), (date '2026-10-30'), (date '2026-11-06'), (date '2026-11-13'), (date '2026-11-20'), (date '2026-11-27')),
l as (select x.xd, w.mkt, w.p26,
        (select min(d) from fr where d > w.p26) - w.p26 as lag_v5,
        least((select min(d) from fr where d > w.p26), greatest(x.xd, w.p26 + 1)) - w.p26 as lag_x
      from w cross join x)
select xd as part2_on, mkt, sum(lag_v5) v5_only, sum(lag_x) with_part2, sum(lag_v5)-sum(lag_x) saved
from l group by 1,2 order by 2,1;
