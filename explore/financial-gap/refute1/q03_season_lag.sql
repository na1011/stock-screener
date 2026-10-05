-- 작년 같은 철(2025-10-01~12-15) 현 편입의 첫 공시일 분포(종목 × 공시일 · rev0 기준) → 올해 날짜로 +1년 옮겨 두 FR 일정의 지연(종목-일)을 잰다.
-- 일정 A(원래): FR 2026-10-30 · 다음 2026-11-25 · 다음 2026-12-21(26일 뒤 평일)
-- 일정 B(결정 5 · D=10-07 16시 손 FR): 다음 11-03(화 · toDays>25 첫 14:15 · 거버넌스 연체 아님 가정) · 다음 11-30
with cur as (select m.stock_id, s.market_code from universe_membership m join stock s on s.id=m.stock_id
             where m.universe_code in ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150') and m.effective_to is null),
f as (select distinct c.market_code, fs.stock_id, (fs.published_at + interval '1 year')::date pub
      from financial_statement fs join cur c using(stock_id)
      where fs.published_at between '2025-10-01' and '2025-12-15' and fs.revision_no=0),
lag as (
  select market_code, pub,
   (select min(d) from unnest(array['2026-10-30','2026-11-25','2026-12-21']::date[]) d where d>=pub) - pub as lag_a,
   (select min(d) from unnest(array['2026-11-03','2026-11-30','2026-12-28']::date[]) d where d>=pub) - pub as lag_b
  from f)
select market_code, count(*) filings, sum(lag_a) lag_a_days, sum(lag_b) lag_b_days,
       count(*) filter (where pub between '2026-10-07' and '2026-10-30') pub_1007_1030,
       count(*) filter (where pub between '2026-10-31' and '2026-11-02') pub_1031_1102,
       count(*) filter (where pub between '2026-11-03' and '2026-11-25') pub_1103_1125
from lag group by 1;
