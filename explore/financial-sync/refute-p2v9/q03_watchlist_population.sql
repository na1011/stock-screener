-- 워치리스트 알림 모집단(결정 6이 지키는 대상) — 항목 · 사용자 · 시장 · 알림 화면
select w.id, w.user_id, s.ticker, s.market_code, w.screen_id, w.last_satisfied, w.last_notified_at, w.created_at::date
from watchlist_item w join stock s on s.id = w.stock_id order by w.id;
select count(*) as items, count(screen_id) as alerting, count(distinct user_id) as users from watchlist_item;
