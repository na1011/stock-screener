-- 10-02 미국 현 편입의 시총 경계(지표 MARKET_CAP · 현 편입만) — 봉 0 새 대표가 경계 안에 들 수 있나의 재료
WITH cur AS (SELECT m.stock_id FROM universe_membership m WHERE m.universe_code='US_TOP1000' AND m.effective_to IS NULL),
v AS (SELECT s.ticker, iv.value FROM indicator_value iv JOIN cur c ON c.stock_id=iv.stock_id JOIN stock s ON s.id=iv.stock_id
      WHERE iv.trade_date='2026-10-02' AND iv.indicator_code='MARKET_CAP')
SELECT count(*), min(value), percentile_cont(0.01) WITHIN GROUP (ORDER BY value) p01, percentile_cont(0.05) WITHIN GROUP (ORDER BY value) p05 FROM v;
-- 형제 옛 대표의 최근 MARKET_CAP(봉 있는 쪽) — 같은 CIK의 새 대표 시총은 가격비만큼
SELECT s.ticker, iv.trade_date, iv.value FROM indicator_value iv JOIN stock s ON s.id=iv.stock_id
WHERE iv.indicator_code='MARKET_CAP' AND s.market_code='US' AND s.ticker IN ('LBTYB','NWS','UHAL','HEI','BTSGU','LLYVK')
  AND iv.trade_date = (SELECT max(trade_date) FROM indicator_value x WHERE x.stock_id=iv.stock_id AND x.indicator_code='MARKET_CAP')
ORDER BY 1;
