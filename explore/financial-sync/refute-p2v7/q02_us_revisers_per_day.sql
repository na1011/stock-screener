-- 2025 미국 10-Q 철: 현 편입(US_TOP1000) 중 그날 공시한 종목(filers)과 그 공시가 앞 판을 정정한 종목(revisers = revision_no>0 행).
-- 단계가 쓰면 정정 갈래(FinancialWriter invalidate · periodStart부터 그 종목 전 지표 삭제)를 타는 종목 수의 근사.
with cur as (select stock_id from universe_membership where effective_to is null and universe_code='US_TOP1000')
select f.published_at, count(distinct f.stock_id) filers,
       count(distinct f.stock_id) filter (where f.revision_no>0) revisers
from financial_statement f
where f.stock_id in (select stock_id from cur) and f.published_at between '2025-10-27' and '2025-11-18'
group by 1 order by 1;
