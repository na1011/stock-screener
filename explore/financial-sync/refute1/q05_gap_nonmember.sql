-- 6월 분기 결손 서명(refute5/q04 꼴): 2026-02~04 기말 행은 있고 2026-05~08 기말 행이 없는데 08-20 뒤에 재무 쓰기가 있었던 미국 종목 — 현 편입 대 비편입(과거 편입·후보)
with w as (select f.stock_id,
  bool_or(f.period_end between '2026-02-01' and '2026-04-30') has_q1,
  bool_or(f.period_end between '2026-05-01' and '2026-08-31') has_q2,
  max(f.created_at) last_write
 from financial_statement f join stock s on s.id=f.stock_id where s.market_code='US' group by 1),
cur as (select distinct stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is null),
ret as (select distinct stock_id from universe_membership where universe_code='US_TOP1000' and effective_to is not null and effective_to >= date '2025-10-01')
select case when c.stock_id is not null then 'current' when r.stock_id is not null then 'past_member_in_window' else 'never_member' end grp,
 count(*) filter (where has_q1) has_q1, count(*) filter (where has_q1 and not has_q2 and last_write>=timestamptz '2026-08-20') gap_sig
from w left join cur c using(stock_id) left join ret r using(stock_id) group by 1 order by 1;
