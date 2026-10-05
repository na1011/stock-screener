-- 미국 봉 중 volume = 0인 행 — 소스가 v=0 봉을 주는지(또는 v 결측을 0으로 접은 것인지) 가르기 위한 표본.
-- 모집단: market_code='US' · 최근 30거래일(2026-08-20 이후)
SELECT count(*) us_bars, count(*) FILTER (WHERE p.volume=0) zero_vol,
       count(DISTINCT p.stock_id) FILTER (WHERE p.volume=0) zero_vol_stocks
FROM price_daily p JOIN stock s ON s.id=p.stock_id WHERE s.market_code='US' AND p.trade_date >= '2026-08-20';
SELECT s.ticker, p.trade_date, p.open, p.high, p.low, p.close, p.volume, p.created_at::timestamp(0)
FROM price_daily p JOIN stock s ON s.id=p.stock_id
WHERE s.market_code='US' AND s.ticker IN ('WSO.B','TAP.A','MOG.B') AND p.trade_date >= '2026-09-14' ORDER BY 1,2;
