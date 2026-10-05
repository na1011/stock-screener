-- (가) 80(기말 03-01~04-10 술어)이 하한인지: 같은 자기 이력 모형으로 #683(09-28) 전 공시 예상인데 그 뒤 기간 행이 없는 미국 현 편입.
SET statement_timeout='60s';
WITH cur AS (SELECT DISTINCT s.id, s.ticker FROM universe_membership m JOIN stock s ON s.id=m.stock_id
             JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active AND s.market_code='US'),
f AS (SELECT stock_id, max(period_end) pe FROM financial_statement GROUP BY 1),
lag AS (SELECT stock_id, percentile_disc(0.5) WITHIN GROUP (ORDER BY published_at - period_end) lagd
        FROM financial_statement WHERE revision_no = 0 AND fiscal_period IN ('Q1','Q2','Q3','FY') AND period_end > date '2023-01-01'
          AND published_at - period_end BETWEEN 1 AND 120 GROUP BY 1),
p AS (SELECT c.*, f.pe, (f.pe + 91 + l.lagd) pred FROM cur c JOIN f ON f.stock_id=c.id JOIN lag l ON l.stock_id=c.id)
SELECT CASE WHEN pe BETWEEN date '2026-03-01' AND date '2026-04-10' THEN 'ga80' WHEN pe <= date '2025-12-31' THEN 'fy_only_or_foreign' ELSE 'other' END grp,
       count(*), string_agg(ticker||':'||pe||'→'||pred, ' ' ORDER BY pred) FILTER (WHERE pe > date '2026-04-10') who
FROM p WHERE pred <= date '2026-09-27' GROUP BY 1 ORDER BY 1;
