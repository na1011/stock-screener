CREATE TEMP TABLE lb(code text, l int);
\copy lb FROM 'lb.tsv'
INSERT INTO lb SELECT code, 986 FROM indicator WHERE code LIKE 'ICHI%';
CREATE TEMP TABLE bnd(ticker text, b date);
INSERT INTO bnd VALUES ('CAI','2025-06-18'),('FLY','2025-08-07'),('NIQ','2025-07-23'),('PPLC','2026-03-04'),('CART','2023-09-19'),('LB','2024-06-28'),('VG','2025-01-24'),('SAIL','2025-02-13'),('CRC','2020-10-28'),('EXE','2021-02-10'),('VAL','2021-05-03'),('WOLF','2025-09-29'),('SEZL','2023-09-14'),
 ('ACT','2021-09-16'),('DJT','2021-09-30'),('LNZA','2021-09-24'),('MBLY','2022-10-26'),('MIR','2020-08-20'),('OWL','2020-12-14'),('SMCI','2020-01-14'),('SN','2023-07-31');
CREATE TEMP TABLE pb AS
SELECT s.id stock_id, b.ticker, p.trade_date, count(*) FILTER (WHERE p.trade_date >= b.b) OVER (PARTITION BY s.id ORDER BY p.trade_date) nb
FROM bnd b JOIN stock s ON s.ticker=b.ticker AND s.market_code='US' JOIN price_daily p ON p.stock_id=s.id;
SELECT CASE WHEN iv.indicator_code LIKE 'ICHI%' THEN 'ICHI(986)' WHEN lb.l>=1261 THEN '5Y/4Y(1261)' WHEN lb.l>=900 THEN 'MACD_V_W(957)' WHEN lb.l>=700 THEN 'TD750' WHEN lb.l>=500 THEN 'BETA(501)' ELSE '<=259' END fam,
  count(*) FILTER (WHERE mem) member_cells, count(*) all_cells, count(DISTINCT pb.stock_id) FILTER (WHERE mem) stocks
FROM pb JOIN indicator_value iv ON iv.stock_id=pb.stock_id AND iv.trade_date=pb.trade_date
JOIN lb ON lb.code=iv.indicator_code
CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=pb.stock_id AND m.universe_code='US_TOP1000' AND m.effective_from<=pb.trade_date AND (m.effective_to IS NULL OR m.effective_to>pb.trade_date)) mem) x
WHERE pb.nb < lb.l AND pb.trade_date >= '2025-09-29'
GROUP BY 1 ORDER BY 2 DESC;
SELECT 'latest-date member cells', count(*) FROM pb JOIN indicator_value iv ON iv.stock_id=pb.stock_id AND iv.trade_date=pb.trade_date JOIN lb ON lb.code=iv.indicator_code
WHERE pb.nb < lb.l AND pb.trade_date = '2026-10-01' AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=pb.stock_id AND m.universe_code='US_TOP1000' AND m.effective_to IS NULL);
