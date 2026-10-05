SET statement_timeout='60s';
-- 상장 후 짧은 기간 안에 국내 유니버스에 들어온 편입 행 — 오래 사는 웹 JVM의 DART corp_code 스냅숏이 못 볼 수 있는 모양
SELECT m.universe_code, s.ticker, s.listed_on, m.effective_from, m.effective_from - s.listed_on AS days_after_listing, m.effective_to
FROM universe_membership m JOIN stock s ON s.id = m.stock_id
WHERE m.universe_code IN ('KOSPI_TOP200','KOSDAQ_TOP150') AND s.listed_on IS NOT NULL
  AND m.effective_from - s.listed_on BETWEEN 0 AND 45
ORDER BY m.effective_from;
SELECT count(*) FILTER (WHERE listed_on IS NULL) AS null_listed, count(*) AS kr_stocks FROM stock WHERE market_code IN ('KOSPI','KOSDAQ');
