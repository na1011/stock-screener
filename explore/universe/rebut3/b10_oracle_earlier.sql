-- R6 형제 오라클(FY>=2018 다른 행 → 0)이 「이른 판 끔」 FR로 0에 닿을 수 있나:
-- 옛 대표에만 있는 판을 새 대표의 같은 (FY,기간) 판 공시일과 견줘 셋으로 가른다.
--  earlier = 새 대표가 같은 기간을 더 늦은 공시일로만 가짐 → 이른 판(FR 기본값 false가 안 넣음)
--  later   = 새 대표가 같은 기간을 더 이른 공시일로만 가짐 → 정정(FR이 넣음)
--  missing = 새 대표에 그 기간이 없음 → 신규(FR이 넣음)
WITH pairs(newrep, oldrep) AS (VALUES ('BTSG','BTSGU'),('NWSA','NWS'),('UHAL.B','UHAL'),('FOX','FOXA'),('LEN.B','LEN'),('HEI.A','HEI'),('LLYVA','LLYVK'),('LBTYA','LBTYB')),
k AS (
  SELECT p.newrep, s.ticker = p.newrep is_new, f.fiscal_year, f.fiscal_period, f.published_at
  FROM pairs p JOIN stock s ON s.market_code='US' AND s.is_active AND s.ticker IN (p.newrep, p.oldrep)
  JOIN financial_statement f ON f.stock_id=s.id AND f.fiscal_year >= 2018
),
only_old AS (
  SELECT a.* FROM k a WHERE NOT a.is_new
   AND NOT EXISTS (SELECT 1 FROM k b WHERE b.newrep=a.newrep AND b.is_new AND (b.fiscal_year,b.fiscal_period,b.published_at)=(a.fiscal_year,a.fiscal_period,a.published_at))
)
SELECT o.newrep, count(*) only_in_old,
  count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM k b WHERE b.newrep=o.newrep AND b.is_new AND (b.fiscal_year,b.fiscal_period)=(o.fiscal_year,o.fiscal_period))) missing_period,
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM k b WHERE b.newrep=o.newrep AND b.is_new AND (b.fiscal_year,b.fiscal_period)=(o.fiscal_year,o.fiscal_period))
                    AND NOT EXISTS (SELECT 1 FROM k b WHERE b.newrep=o.newrep AND b.is_new AND (b.fiscal_year,b.fiscal_period)=(o.fiscal_year,o.fiscal_period) AND b.published_at < o.published_at)) earlier_vintage,
  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM k b WHERE b.newrep=o.newrep AND b.is_new AND (b.fiscal_year,b.fiscal_period)=(o.fiscal_year,o.fiscal_period) AND b.published_at < o.published_at)) later_or_mid_vintage
FROM only_old o GROUP BY 1 ORDER BY 1;
