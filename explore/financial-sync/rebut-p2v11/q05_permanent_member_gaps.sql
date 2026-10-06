-- 결정 5 (ii)가 「일시」라고 부르는 구멍 중 메워지지 않는 것: 편입 구간 안 · 시장 지표 달력 날짜 D에 ADJ_CLOSE가 없는데
-- 그 뒤 날짜에는 ADJ_CLOSE가 있는 (종목, D) — 지금까지 안 메워졌다(뒤 봉은 이미 들어왔다). 항목 워터마크가 거기서 멈추면 영영 못 간다.
-- 모집단: 지금 활성 유니버스 편입 종목 · 지표 달력(indicator_trade_date) 날짜 · 보존 창 전체.
set timezone='Asia/Seoul';
with mem as (
  select m.stock_id, s.market_code, s.ticker, m.effective_from f, m.effective_to t
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
  where s.is_active
), d as (select t.market_code, t.trade_date from indicator_trade_date t),
gaps as (
  select distinct mem.market_code, mem.ticker, mem.stock_id, d.trade_date
  from mem join d on d.market_code = mem.market_code and d.trade_date >= mem.f and (mem.t is null or d.trade_date < mem.t)
  where not exists (select 1 from indicator_value iv where iv.stock_id = mem.stock_id and iv.trade_date = d.trade_date and iv.indicator_code = 'ADJ_CLOSE')
    and exists (select 1 from indicator_value iv where iv.stock_id = mem.stock_id and iv.trade_date > d.trade_date and iv.indicator_code = 'ADJ_CLOSE')
)
select market_code, count(*) stock_days, count(distinct stock_id) stocks,
       count(*) filter (where trade_date >= '2026-08-10') since_0810,
       count(distinct stock_id) filter (where trade_date >= '2026-08-10') stocks_since_0810
from gaps group by 1 order by 1;
