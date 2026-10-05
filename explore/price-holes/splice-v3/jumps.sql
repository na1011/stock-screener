-- 접합 v3 감시 ③ 후보 — 하루 종가 배수가 SUPPORT 경계(×2 / ×0.5 · PriceDiscontinuity) 밖이고 원장 행이 근접중복 창(±5 봉) 안에 없는 쌍.
-- 모집단: windowMemberIds 술어 ∩ 미국. 평봉 → 실거래 경계는 평봉 탐지(detect.sql)가 따로 센다 — 여기서는 두 봉 다 실거래인 쌍만.
WITH pop AS (
  SELECT DISTINCT s.id, s.ticker FROM universe_membership m
  JOIN stock s ON s.id = m.stock_id JOIN universe u ON u.code = m.universe_code AND u.is_active
  WHERE s.is_active AND s.market_code = 'US'
    AND (m.effective_to IS NULL
         OR (m.effective_from <= (SELECT max(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)
             AND m.effective_to > (SELECT min(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)))),
b AS (
  SELECT p.stock_id, pop.ticker, p.trade_date, p.close, p.volume,
         lag(p.close) OVER w pc, lag(p.volume) OVER w pv, lag(p.trade_date) OVER w pd,
         row_number() OVER w rn
  FROM price_daily p JOIN pop ON pop.id = p.stock_id WINDOW w AS (PARTITION BY p.stock_id ORDER BY p.trade_date))
SELECT b.ticker, b.pd, b.trade_date, b.pc, b.close, round(b.close / b.pc, 4) r,
  (SELECT string_agg(ca.type || ':' || ca.status, ',') FROM corporate_action ca
    WHERE ca.stock_id = b.stock_id
      AND ca.effective_date BETWEEN (SELECT x.trade_date FROM b x WHERE x.stock_id = b.stock_id AND x.rn = greatest(b.rn - 5, 1))
                                AND (SELECT coalesce(max(x.trade_date), b.trade_date) FROM b x WHERE x.stock_id = b.stock_id AND x.rn <= b.rn + 5)) ledger
FROM b
WHERE b.pc > 0 AND b.volume > 0 AND b.pv > 0 AND (b.close / b.pc >= 2 OR b.close / b.pc <= 0.5)
ORDER BY abs(ln(b.close / b.pc)) DESC;
