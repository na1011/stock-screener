-- 0부가 무거운 잡 위상을 옮긴다: 옛 위상(FR 10-30 → 11-25 · FSR 10-20 → 11-17) 대 새 위상(FR 10-08 → 11-03 → 11-30 · FSR 10-09 → 11-04 → 12-01).
-- 작년 같은 철(2025 Q3)의 첫 공시일 분포로 「어느 실행이 처음 받는가」를 근사한다(현 편입 · 첫 판 = 그 라벨 최소 published_at).
with cur as (select distinct m.stock_id, s.market_code from universe_membership m join stock s on s.id=m.stock_id
             where m.effective_to is null and m.universe_code in ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150')),
q3 as (select f.stock_id, min(f.published_at) first_pub
       from financial_statement f join cur on cur.stock_id=f.stock_id
       where f.fiscal_year=2025 and f.fiscal_period='Q3' group by 1)
select case when c.market_code='US' then 'US' else 'KR' end mkt,
       count(*) n,
       count(*) filter (where first_pub <= date '2025-10-07') le_1007,
       count(*) filter (where first_pub between date '2025-10-08' and date '2025-10-29') d1008_1029,
       count(*) filter (where first_pub between date '2025-10-30' and date '2025-11-02') d1030_1102,
       count(*) filter (where first_pub between date '2025-11-03' and date '2025-11-16') d1103_1116,
       count(*) filter (where first_pub between date '2025-11-17' and date '2025-11-24') d1117_1124,
       count(*) filter (where first_pub between date '2025-11-25' and date '2025-11-29') d1125_1129,
       count(*) filter (where first_pub >= date '2025-11-30') ge_1130
from q3 join cur c on c.stock_id=q3.stock_id group by 1 order by 1;
