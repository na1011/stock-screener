-- 지울 봉 수: (1) 경계 파일 앞 봉 (2) 미국 머리 평봉(저장 이력의 첫 실거래 앞 · 거래량 0 · 시고저종 같음 · 경계 파일 종목 제외).
-- 종목 id를 먼저 풀어 (stock_id, trade_date) 기본키 범위로 읽는다.
CREATE TEMP TABLE bnd(ticker text, boundary date);
\copy bnd FROM PROGRAM 'cut -f2,3 boundaries-v3.tsv'
SELECT 'BOUNDARY', b.ticker, count(p.trade_date), min(p.trade_date), max(p.trade_date)
FROM bnd b JOIN stock s ON s.ticker = b.ticker AND s.market_code = 'US'
LEFT JOIN LATERAL (SELECT trade_date FROM price_daily x WHERE x.stock_id = s.id AND x.trade_date < b.boundary) p ON true
GROUP BY b.ticker
UNION ALL
SELECT 'HEAD_FLAT', h.ticker, h.n, h.s, h.e FROM (
  SELECT s.ticker, count(*) n, min(p.trade_date) s, max(p.trade_date) e
  FROM stock s
  JOIN LATERAL (SELECT min(trade_date) d FROM price_daily x WHERE x.stock_id = s.id AND x.volume > 0) fr ON true
  JOIN price_daily p ON p.stock_id = s.id AND (fr.d IS NULL OR p.trade_date < fr.d)
  WHERE s.market_code = 'US' AND s.ticker NOT IN (SELECT ticker FROM bnd)
    AND p.volume = 0 AND p.open = p.high AND p.high = p.low AND p.low = p.close
  GROUP BY s.ticker) h
ORDER BY 1, 2;
