-- 접합 v3 탐지 — 봉의 성질만 센다(임계를 미리 고르지 않는다 · 길이·단절·위치를 열로 낸다).
-- 모집단: StockRegistrar.windowMemberIds와 같은 술어(활성 유니버스 · 활성 종목 · IN_RETENTION_WINDOW) ∩ 시장 :mkt.
-- 출력 한 행 = 평봉 구간 하나(HEAD · MID · TAIL) 또는 간격 하나(GAP).
--   평봉 = volume 0 이고 시고저종이 같다.
--   HEAD = 그 종목 저장 이력의 첫 실거래(volume > 0) 앞 · MID = 실거래 사이 · TAIL = 마지막 실거래 뒤.
--   GAP  = 이웃한 두 봉 사이의 시장 거래일 수 - 1 (달력 = 그 시장 봉이 300개 넘는 날).
--   end_ratio = 구간 뒤 첫 실거래 종가 / 구간 종가 · start_ratio = 구간 종가 / 구간 앞 마지막 실거래 종가.
--   flat_runs_5 = 그 종목의 5봉 이상 MID 평봉 구간 수(같은 증권의 거래 드문 클래스주를 가르는 성질 — 분포를 함께 낸다).
WITH pop AS (
  SELECT DISTINCT s.id, s.ticker FROM universe_membership m
  JOIN stock s ON s.id = m.stock_id
  JOIN universe u ON u.code = m.universe_code AND u.is_active
  WHERE s.is_active AND s.market_code = :'mkt'
    AND (m.effective_to IS NULL
         OR (m.effective_from <= (SELECT max(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)
             AND m.effective_to > (SELECT min(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)))),
cal AS (
  SELECT p.trade_date, row_number() OVER (ORDER BY p.trade_date) k
  FROM price_daily p JOIN stock s ON s.id = p.stock_id
  WHERE s.market_code = :'mkt' GROUP BY p.trade_date HAVING count(*) > 300),
bars AS (
  SELECT p.stock_id, pop.ticker, p.trade_date, p.close, p.volume,
         (p.volume = 0 AND p.open = p.high AND p.high = p.low AND p.low = p.close) AS flat,
         row_number() OVER w AS rn
  FROM price_daily p JOIN pop ON pop.id = p.stock_id
  WINDOW w AS (PARTITION BY p.stock_id ORDER BY p.trade_date)),
firstreal AS (SELECT stock_id, min(trade_date) d FROM bars WHERE NOT flat GROUP BY stock_id),
lastreal AS (SELECT stock_id, max(trade_date) d FROM bars WHERE NOT flat GROUP BY stock_id),
grp AS (
  SELECT b.*, b.rn - row_number() OVER (PARTITION BY b.stock_id ORDER BY b.trade_date) g
  FROM bars b WHERE b.flat),
runs AS (
  SELECT stock_id, min(ticker) ticker, min(trade_date) s, max(trade_date) e, count(*) n,
         (array_agg(close ORDER BY trade_date DESC))[1] c
  FROM grp GROUP BY stock_id, g),
runx AS (
  SELECT r.*,
    CASE WHEN fr.d IS NULL OR r.e < fr.d THEN 'HEAD' WHEN r.s > lr.d THEN 'TAIL' ELSE 'MID' END kind,
    (SELECT b.close FROM bars b WHERE b.stock_id = r.stock_id AND NOT b.flat AND b.trade_date < r.s ORDER BY b.trade_date DESC LIMIT 1) prev_c,
    (SELECT b.close FROM bars b WHERE b.stock_id = r.stock_id AND NOT b.flat AND b.trade_date > r.e ORDER BY b.trade_date LIMIT 1) next_c,
    (SELECT b.trade_date FROM bars b WHERE b.stock_id = r.stock_id AND NOT b.flat AND b.trade_date > r.e ORDER BY b.trade_date LIMIT 1) next_d
  FROM runs r LEFT JOIN firstreal fr ON fr.stock_id = r.stock_id LEFT JOIN lastreal lr ON lr.stock_id = r.stock_id),
cls AS (SELECT stock_id, count(*) FILTER (WHERE kind = 'MID' AND n >= 5) flat_runs_5 FROM runx GROUP BY stock_id),
gaps AS (
  SELECT b.stock_id, b.ticker, lag(b.trade_date) OVER w s, b.trade_date e,
         c2.k - lag(c1.k) OVER w - 1 AS missing,
         lag(b.close) OVER w prev_c, b.close next_c
  FROM bars b JOIN cal c2 ON c2.trade_date = b.trade_date JOIN cal c1 ON c1.trade_date = b.trade_date
  WINDOW w AS (PARTITION BY b.stock_id ORDER BY b.trade_date))
SELECT x.ticker, x.kind, x.s, x.e, x.n, x.next_d, x.prev_c, x.c, x.next_c,
       round(x.next_c / nullif(x.c, 0), 4) end_ratio, round(x.c / nullif(x.prev_c, 0), 4) start_ratio,
       coalesce(cl.flat_runs_5, 0) flat_runs_5
FROM runx x LEFT JOIN cls cl ON cl.stock_id = x.stock_id
UNION ALL
SELECT g.ticker, 'GAP', g.s, g.e, g.missing, g.e, g.prev_c, NULL, g.next_c,
       round(g.next_c / nullif(g.prev_c, 0), 4), NULL, coalesce(cl.flat_runs_5, 0)
FROM gaps g LEFT JOIN cls cl ON cl.stock_id = g.stock_id
WHERE g.missing > 5
ORDER BY 1, 3;
