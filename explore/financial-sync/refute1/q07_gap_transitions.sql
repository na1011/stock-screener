-- gap80 종목의 미국 편입 행 중 6월 10-Q 공시(07-22~08-07) 뒤에 열리거나 닫힌 것 — Q1 주식수로 판정된 전환
with g as (select unnest(string_to_array(:'lst', ',')) t)
select s.ticker, m.origin, m.effective_from, m.effective_to
from universe_membership m join stock s on s.id=m.stock_id join g on g.t=s.ticker
where m.universe_code='US_TOP1000' and (m.effective_from >= date '2026-07-22' or m.effective_to >= date '2026-07-22')
  and not (m.effective_to is null and m.effective_from = (select min(effective_from) from universe_membership where origin='OBSERVED' and universe_code='US_TOP1000'))
order by 1,3;
