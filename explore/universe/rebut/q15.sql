\i base.sql
\pset format unaligned
\pset fieldsep '\t'
WITH donors AS (SELECT DISTINCT lc.cik FROM lc JOIN sc ON sc.stock_id=lc.id WHERE NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770') AND lc.cik IS NOT NULL),
inlisted AS (SELECT replace(ticker,'.','-') t FROM listed UNION SELECT ticker FROM listed)
SELECT c.exch, count(*) FROM cik c WHERE c.cik IN (SELECT cik FROM donors) AND c.ticker NOT IN (SELECT t FROM inlisted) GROUP BY 1 ORDER BY 2 DESC;
WITH donors AS (SELECT DISTINCT lc.cik FROM lc JOIN sc ON sc.stock_id=lc.id WHERE NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770') AND lc.cik IS NOT NULL),
inlisted AS (SELECT replace(ticker,'.','-') t FROM listed UNION SELECT ticker FROM listed)
SELECT c.cik, c.ticker, c.name, c.exch FROM cik c WHERE c.cik IN (SELECT cik FROM donors) AND c.ticker NOT IN (SELECT t FROM inlisted) AND c.exch IN ('NYSE','Nasdaq','CBOE','') ORDER BY 1 LIMIT 40;
SELECT s.ticker, round(sc.shares) FROM sc JOIN stock s ON s.id=sc.stock_id WHERE s.ticker IN ('AQN','AQNB');
