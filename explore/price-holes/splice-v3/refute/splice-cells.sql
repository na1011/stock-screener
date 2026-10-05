CREATE TEMP TABLE lb(code text, l int);
\copy lb FROM 'lb.tsv'
INSERT INTO lb SELECT code, 986 FROM indicator WHERE code LIKE 'ICHI%';
CREATE TEMP TABLE bnd(ticker text, b date);
INSERT INTO bnd VALUES ('CAI','2025-06-18'),('FLY','2025-08-07'),('NIQ','2025-07-23'),('PPLC','2026-03-04'),('CART','2023-09-19'),('LB','2024-06-28'),('VG','2025-01-24'),('SAIL','2025-02-13'),('CRC','2020-10-28'),('EXE','2021-02-10'),('VAL','2021-05-03'),('WOLF','2025-09-29'),('SEZL','2023-09-14'),
 ('ACT','2021-09-16'),('DJT','2021-09-30'),('LNZA','2021-09-24'),('MBLY','2022-10-26'),('MIR','2020-08-20'),('OWL','2020-12-14'),('SMCI','2020-01-14'),('SN','2023-07-31');
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
