-- jumps.sql과 같은 술어(모집단 · ×2/×0.5 · 두 봉 실거래 · 원장 ±5봉) — 상관 부질의를 창 함수로 바꿨다.
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
         coalesce(lag(p.trade_date,5) OVER w, first_value(p.trade_date) OVER w) lo,
         coalesce(lead(p.trade_date,5) OVER w, p.trade_date) hi0,
         max(p.trade_date) OVER (PARTITION BY p.stock_id) mx
  FROM price_daily p JOIN pop ON pop.id = p.stock_id WINDOW w AS (PARTITION BY p.stock_id ORDER BY p.trade_date)),
j AS (SELECT * FROM b WHERE b.pc > 0 AND b.volume > 0 AND b.pv > 0 AND (b.close / b.pc >= 2 OR b.close / b.pc <= 0.5))
SELECT j.ticker, j.pd, j.trade_date, j.pc, j.close, round(j.close / j.pc, 4) r,
  (SELECT string_agg(ca.type || ':' || ca.status, ',') FROM corporate_action ca
    WHERE ca.stock_id = j.stock_id AND ca.effective_date BETWEEN j.lo AND (CASE WHEN j.hi0 = j.trade_date THEN j.mx ELSE j.hi0 END)) ledger
FROM j ORDER BY abs(ln(j.close / j.pc)) DESC;
