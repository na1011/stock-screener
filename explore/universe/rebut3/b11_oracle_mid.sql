-- b10 정밀화: 옛 대표에만 있는 판을 새 대표 같은 기간 판 공시일 범위와 견준다(earlier < min · mid 사이 · later > max · missing)
WITH pairs(newrep, oldrep) AS (VALUES ('BTSG','BTSGU'),('NWSA','NWS'),('UHAL.B','UHAL'),('FOX','FOXA'),('LEN.B','LEN'),('HEI.A','HEI'),('LLYVA','LLYVK'),('LBTYA','LBTYB')),
k AS (
  SELECT p.newrep, s.ticker = p.newrep is_new, f.fiscal_year, f.fiscal_period, f.published_at
  FROM pairs p JOIN stock s ON s.market_code='US' AND s.is_active AND s.ticker IN (p.newrep, p.oldrep)
  JOIN financial_statement f ON f.stock_id=s.id AND f.fiscal_year >= 2018
),
newrng AS (SELECT newrep, fiscal_year, fiscal_period, min(published_at) lo, max(published_at) hi FROM k WHERE is_new GROUP BY 1,2,3),
only_old AS (
  SELECT a.* FROM k a WHERE NOT a.is_new
   AND NOT EXISTS (SELECT 1 FROM k b WHERE b.newrep=a.newrep AND b.is_new AND (b.fiscal_year,b.fiscal_period,b.published_at)=(a.fiscal_year,a.fiscal_period,a.published_at))
)
SELECT o.newrep, count(*) only_in_old,
  count(*) FILTER (WHERE r.lo IS NULL) missing_period,
  count(*) FILTER (WHERE o.published_at < r.lo) earlier_than_all,
  count(*) FILTER (WHERE o.published_at > r.lo AND o.published_at < r.hi) between_,
  count(*) FILTER (WHERE o.published_at > r.hi) later_than_all
FROM only_old o LEFT JOIN newrng r USING (newrep, fiscal_year, fiscal_period)
GROUP BY 1 ORDER BY 1;
