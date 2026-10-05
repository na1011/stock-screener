CREATE TEMP TABLE lb(code text, l int);
\copy lb FROM 'lb.tsv'
INSERT INTO lb SELECT code, 986 FROM indicator WHERE code LIKE 'ICHI%';
CREATE TEMP TABLE bnd(ticker text, b date);
INSERT INTO bnd VALUES ('CR','2023-04-04'),('BAM','2022-12-12');
CREATE TEMP TABLE pb AS
SELECT s.id stock_id, b.ticker, p.trade_date, count(*) FILTER (WHERE p.trade_date >= b.b) OVER (PARTITION BY s.id ORDER BY p.trade_date) nb
FROM bnd b JOIN stock s ON s.ticker=b.ticker AND s.market_code='US' JOIN price_daily p ON p.stock_id=s.id;
-- cells existing whose post-boundary bar count < lookback
SELECT pb.ticker,
  count(*) FILTER (WHERE mem) member_cells,
  count(*) all_cells,
  count(*) FILTER (WHERE mem AND iv.indicator_code IN ('RETURN_52W','EXCESS_RETURN_52W','PCT_FROM_52W_HIGH','PCT_FROM_52W_LOW','EXCESS_RETURN_52W_PCTL')) m52
FROM pb JOIN indicator_value iv ON iv.stock_id=pb.stock_id AND iv.trade_date=pb.trade_date
JOIN lb ON lb.code=iv.indicator_code
CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=pb.stock_id AND m.universe_code='US_TOP1000' AND m.effective_from<=pb.trade_date AND (m.effective_to IS NULL OR m.effective_to>pb.trade_date)) mem) x
WHERE pb.nb < lb.l AND pb.trade_date >= '2025-09-29'
GROUP BY ROLLUP(pb.ticker) ORDER BY 2 DESC;
