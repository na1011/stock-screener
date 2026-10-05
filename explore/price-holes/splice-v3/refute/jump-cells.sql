CREATE TEMP TABLE lb(code text, l int);
\copy lb FROM 'lb.tsv'
INSERT INTO lb SELECT code, 986 FROM indicator WHERE code LIKE 'ICHI%';
CREATE TEMP TABLE j(ticker text, e date, r numeric);
\copy j FROM 'nojumps.tsv'
CREATE TEMP TABLE pb AS
SELECT j.ticker, j.e, j.r, s.id stock_id, p.trade_date,
  count(*) FILTER (WHERE p.trade_date >= j.e) OVER (PARTITION BY s.id, j.e ORDER BY p.trade_date) nb
FROM j JOIN stock s ON s.ticker=j.ticker AND s.market_code='US' JOIN price_daily p ON p.stock_id=s.id
WHERE p.trade_date >= j.e;
SELECT pb.ticker, pb.e, pb.r,
  count(*) FILTER (WHERE mem) member_cells, count(*) all_cells
FROM pb JOIN indicator_value iv ON iv.stock_id=pb.stock_id AND iv.trade_date=pb.trade_date
JOIN lb ON lb.code=iv.indicator_code
CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=pb.stock_id AND m.universe_code='US_TOP1000' AND m.effective_from<=pb.trade_date AND (m.effective_to IS NULL OR m.effective_to>pb.trade_date)) mem) x
WHERE pb.nb <= lb.l AND pb.trade_date >= '2025-09-29'
GROUP BY ROLLUP((pb.ticker, pb.e, pb.r)) ORDER BY 4 DESC NULLS FIRST;
