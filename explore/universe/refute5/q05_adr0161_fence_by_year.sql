-- ADR-0161 펜스(원 공시 판 누락)와 같은 술어 — 현 편입 US 전체 · 결과를 F6 대상 13 / 나머지 · 회계연도 구간(≤2017 = 8년 창 밖)으로 가른다.
SET statement_timeout='60s';
WITH firsts AS (SELECT f.stock_id, f.fiscal_year, f.fiscal_period, min(f.published_at) first_pub FROM financial_statement f JOIN stock s ON s.id = f.stock_id
  WHERE s.market_code NOT IN ('KOSPI','KOSDAQ') AND EXISTS (SELECT 1 FROM universe_membership um WHERE um.stock_id = f.stock_id AND um.universe_code = 'US_TOP1000' AND um.effective_to IS NULL) GROUP BY 1,2,3),
flagged AS (SELECT s.ticker::text ticker, a.fiscal_year, a.fiscal_period FROM firsts a JOIN firsts b ON b.stock_id = a.stock_id AND b.fiscal_period = a.fiscal_period AND b.fiscal_year = a.fiscal_year + 1
  JOIN stock s ON s.id = a.stock_id WHERE a.first_pub >= b.first_pub)
SELECT (ticker IN ('AGX','AXTI','BB','FTDR','GVA','IOVA','KLIC','KOD','LAUR','NAVN','NJR','TFX','VSH')) f6_target, (fiscal_year <= 2017) outside_8y,
       count(*) labels, count(DISTINCT ticker) stocks, string_agg(DISTINCT ticker, ' ') who
FROM flagged GROUP BY 1,2 ORDER BY 1,2;
