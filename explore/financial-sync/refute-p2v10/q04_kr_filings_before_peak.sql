-- v10 결정 5 「반영 지연 관찰을 11-17 전에(10월 국내 정정·분기 공시로)」의 모집단 하한:
-- 작년(2025) 같은 창(2부 배포 목표 10-26 뒤 ~ 피크 전 11-13)에 공시일(published_at)이 든 국내 현 편입 재무 행 —
-- 저장된 판(is_latest 여부 무관 · 행 = 접수번호 하나의 한 기간)만 세므로 우리가 받은 것의 하한이다(원장에 묻지 않았다).
with kr as (
  select s.id from stock s
  join universe_membership m on m.stock_id=s.id and m.effective_to is null
  join universe u on u.code=m.universe_code and u.is_active
  where s.market_code in ('KOSPI','KOSDAQ') group by s.id
)
select case when f.published_at between '2025-10-26' and '2025-11-13' then 'a 2025-10-26~11-13(배포 뒤 · 피크 전)'
            when f.published_at between '2025-11-14' and '2025-11-17' then 'b 2025-11-14~11-17(피크)'
            when f.published_at between '2025-10-01' and '2025-10-25' then 'c 2025-10-01~10-25' end win,
       f.fiscal_period, count(*) rows_, count(distinct f.stock_id) stocks
from financial_statement f join kr on kr.id=f.stock_id
where f.published_at between '2025-10-01' and '2025-11-17'
group by 1,2 order by 1,2;
