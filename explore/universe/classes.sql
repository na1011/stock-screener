-- 같은 CIK 안에 명부(US_TOP1000 · 보존 창 술어) 티커와 보통주로 보이는 형제 티커가 함께 있는 발행사 — 형제의 유동성(최근 60봉 평균 거래대금)과 함께.
CREATE TEMP TABLE cik(cik bigint, ticker text, exch text);
\copy cik FROM 'cik.csv' CSV
WITH us AS (SELECT s.id, s.ticker, s.name FROM stock s WHERE s.market_code = 'US' AND s.is_active),
roster AS (
  SELECT DISTINCT s.id FROM universe_membership m JOIN stock s ON s.id = m.stock_id
  WHERE m.universe_code = 'US_TOP1000' AND s.market_code = 'US'
    AND (m.effective_to IS NULL OR (m.effective_from <= (SELECT max(trade_date) FROM indicator_trade_date WHERE market_code = 'US')
                                    AND m.effective_to > (SELECT min(trade_date) FROM indicator_trade_date WHERE market_code = 'US')))),
j AS (
  SELECT c.cik, us.ticker, us.name, (us.id IN (SELECT id FROM roster)) in_roster,
         (SELECT round(avg(p.close * p.volume) / 1e6, 2) FROM (SELECT close, volume FROM price_daily x WHERE x.stock_id = us.id ORDER BY trade_date DESC LIMIT 60) p) dv60m,
         (SELECT count(*) FROM price_daily x WHERE x.stock_id = us.id) bars
  FROM us JOIN cik c ON c.ticker = replace(us.ticker, '.', '-')
  WHERE upper(us.name) !~ '\m(NOTES?|DEBENTURES?|DEPOSITARY|PREFERRED|PFD|WARRANTS?|RIGHTS?|SUBORDINATED|PREFERENCE|CAPITAL SECURITIES)\M')
SELECT * FROM j WHERE cik IN (SELECT cik FROM j GROUP BY cik HAVING count(*) > 1 AND bool_or(in_roster))
ORDER BY cik, ticker;
