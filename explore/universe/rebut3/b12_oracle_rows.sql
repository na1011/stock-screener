-- b11의 「이른·사이」 판(이른 판 끔 FR이 새 대표에 못 넣는 것) 목록 — 공시일이 보존 창(US 2025-09-29~) 안팎인지
WITH pairs(newrep, oldrep) AS (VALUES ('BTSG','BTSGU'),('NWSA','NWS'),('UHAL.B','UHAL'),('FOX','FOXA'),('LEN.B','LEN'),('HEI.A','HEI'),('LLYVA','LLYVK'),('LBTYA','LBTYB')),
k AS (
  SELECT p.newrep, p.oldrep, s.ticker = p.newrep is_new, f.fiscal_year, f.fiscal_period, f.published_at, f.revision_no, f.created_at::date cdate
  FROM pairs p JOIN stock s ON s.market_code='US' AND s.is_active AND s.ticker IN (p.newrep, p.oldrep)
  JOIN financial_statement f ON f.stock_id=s.id AND f.fiscal_year >= 2018
),
newrng AS (SELECT newrep, fiscal_year, fiscal_period, min(published_at) lo, max(published_at) hi FROM k WHERE is_new GROUP BY 1,2,3)
SELECT a.newrep, a.oldrep, a.fiscal_year, a.fiscal_period, a.published_at old_only_pub, r.lo new_min_pub, r.hi new_max_pub, a.cdate old_row_created
FROM k a JOIN newrng r USING (newrep, fiscal_year, fiscal_period)
WHERE NOT a.is_new AND a.published_at < r.hi
  AND NOT EXISTS (SELECT 1 FROM k b WHERE b.newrep=a.newrep AND b.is_new AND (b.fiscal_year,b.fiscal_period,b.published_at)=(a.fiscal_year,a.fiscal_period,a.published_at))
ORDER BY 1,3,4,5;
