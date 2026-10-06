-- 「D에 ADJ_CLOSE 없음 = 일시 구멍 → 항목 기록 없음」이 편출 종목에도 걸리는가 — 편출 뒤 날짜에는 지표가 안 써진다(③은 현 편입만).
-- 지금 활성 편입이 아닌 종목 중 08-10 뒤 편출된 수 · 그 종목의 편출 뒤 지표 달력 날짜에 ADJ_CLOSE가 있는 비율
set timezone='Asia/Seoul';
with ex as (
  select m.stock_id, s.market_code, s.ticker, max(m.effective_to) left_at
  from universe_membership m join stock s on s.id=m.stock_id
  where m.effective_to >= '2026-08-10'
    and not exists (select 1 from universe_membership m2 join universe u on u.code=m2.universe_code and u.is_active
                    where m2.stock_id=m.stock_id and m2.effective_to is null)
  group by 1,2,3
)
select ex.market_code, count(*) exited_now_out,
  sum((select count(*) from indicator_trade_date t where t.market_code=ex.market_code and t.trade_date >= ex.left_at)) days_after_exit,
  sum((select count(*) from indicator_value iv where iv.stock_id=ex.stock_id and iv.indicator_code='ADJ_CLOSE' and iv.trade_date >= ex.left_at)) adj_close_rows_after_exit
from ex group by 1 order by 1;
select 'watchlist items on non-member stocks', count(*) from watchlist_item w
 where not exists (select 1 from universe_membership m join universe u on u.code=m.universe_code and u.is_active where m.stock_id=w.stock_id and m.effective_to is null);
select 'watchlist items total', count(*), count(*) filter (where screen_id is not null) alerting from watchlist_item;
