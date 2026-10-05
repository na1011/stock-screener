-- (계획 밖 · 보고만) 미국: 최신 판 OCF NULL · 같은 기간 옛 판 OCF 있음 — 분포
SELECT l.fiscal_period, count(*) cells, count(DISTINCT l.stock_id) stocks,
       count(*) FILTER (WHERE l.fiscal_year>=2023) fy23p,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=l.stock_id AND m.universe_code='US_TOP1000' AND m.effective_to IS NULL)) cur_member
FROM financial_statement l JOIN stock s ON s.id=l.stock_id
WHERE s.market_code='US' AND l.is_latest AND l.operating_cash_flow IS NULL
  AND EXISTS (SELECT 1 FROM financial_statement o WHERE o.stock_id=l.stock_id AND o.fiscal_year=l.fiscal_year
              AND o.fiscal_period=l.fiscal_period AND NOT o.is_latest AND o.operating_cash_flow IS NOT NULL)
GROUP BY 1 ORDER BY 1;
SELECT s.ticker, l.fiscal_year, l.fiscal_period, l.revision_no, l.published_at, l.report_code
FROM financial_statement l JOIN stock s ON s.id=l.stock_id
WHERE s.market_code='US' AND l.is_latest AND l.operating_cash_flow IS NULL AND l.fiscal_year>=2024
  AND EXISTS (SELECT 1 FROM financial_statement o WHERE o.stock_id=l.stock_id AND o.fiscal_year=l.fiscal_year
              AND o.fiscal_period=l.fiscal_period AND NOT o.is_latest AND o.operating_cash_flow IS NOT NULL)
ORDER BY random() LIMIT 8;
