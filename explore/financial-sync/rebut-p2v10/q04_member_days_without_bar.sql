-- 「그 종목의 D에 ADJ_CLOSE 행 없음 → 일시 구멍 → 항목 기록 없음」 성질이 지금 걸리는 (종목, D) — 현 편입 · 편입 구간 안 ·
-- 시장 지표 달력의 날짜 D에 그 종목 ADJ_CLOSE 행이 없음(지금 시점). 끝내 안 메워지는 진짜 부재(정지 등)는 보류 행이 255거래일 남는다.
set timezone='Asia/Seoul';
with mem as (
  select m.stock_id, s.market_code, s.ticker, min(m.effective_from) f
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
  where m.effective_to is null and s.is_active group by 1,2,3
), d as (
  select t.market_code, t.trade_date from indicator_trade_date t where t.trade_date >= '2026-08-10'
)
select mem.market_code, count(*) missing_stock_days, count(distinct mem.stock_id) stocks
from mem join d on d.market_code = mem.market_code and d.trade_date >= mem.f
where not exists (select 1 from indicator_value iv where iv.stock_id = mem.stock_id and iv.trade_date = d.trade_date and iv.indicator_code = 'ADJ_CLOSE')
group by 1 order by 1;
