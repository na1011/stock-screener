-- 몰린 날의 국내 종목 수를 모집단 셋으로: (가) v6이 쓴 「현 편입 ∪ 과거」(국내 stock 전부) (나) 그날 편입(단계의 실제 모집단 꼴) (다) 지금 편입
with kr_u as (select code from universe where market_code in ('KOSPI','KOSDAQ') and is_active),
d as (select fs.published_at::date d, fs.stock_id from financial_statement fs join stock s on s.id=fs.stock_id
      where s.market_code in ('KOSPI','KOSDAQ') and fs.published_at::date in ('2025-05-15','2025-08-14','2025-11-14','2026-03-18','2026-05-15','2026-08-14','2025-11-13','2026-08-13','2026-05-14')
      group by 1,2)
select d.d, to_char(d.d,'Dy') dow,
       count(distinct d.stock_id) all_kr,
       count(distinct d.stock_id) filter (where exists (select 1 from universe_membership m where m.stock_id=d.stock_id and m.universe_code in (select code from kr_u) and m.effective_from<=d.d and (m.effective_to is null or m.effective_to>d.d))) member_that_day,
       count(distinct d.stock_id) filter (where exists (select 1 from universe_membership m where m.stock_id=d.stock_id and m.universe_code in (select code from kr_u) and m.effective_to is null)) member_now
from d group by 1 order by 1;
