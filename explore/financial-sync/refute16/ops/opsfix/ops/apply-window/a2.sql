WITH f AS (
  SELECT f.*, (s.market_code IN ('KOSPI','KOSDAQ')) kr,
         row_number() OVER (PARTITION BY f.stock_id, f.fiscal_year, f.fiscal_period ORDER BY f.published_at, f.revision_no, f.id) - 1 AS expected,
         max(f.published_at) OVER (PARTITION BY f.stock_id, f.fiscal_year, f.fiscal_period) AS max_pub,
         count(*) FILTER (WHERE f.is_latest) OVER (PARTITION BY f.stock_id, f.fiscal_year, f.fiscal_period) AS latest_n,
         lag(f.published_at) OVER (PARTITION BY f.stock_id, f.fiscal_year, f.fiscal_period ORDER BY f.revision_no) AS prev_pub
  FROM financial_statement f JOIN stock s ON s.id = f.stock_id)
SELECT kr,
  count(*) FILTER (WHERE revision_no <> expected) AS 번호_불연속행,
  count(*) FILTER (WHERE prev_pub IS NOT NULL AND prev_pub >= published_at) AS 역전_또는_동일공시일,
  count(*) FILTER (WHERE latest_n <> 1) AS 최신수_어긋남행,
  count(*) FILTER (WHERE is_latest AND published_at <> max_pub) AS 최신이_최대공시일아님
FROM f GROUP BY kr ORDER BY kr;
