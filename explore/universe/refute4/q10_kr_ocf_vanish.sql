-- 국내: 최신 판의 영업현금흐름(전체본 FS만 채움)이 NULL인데 같은 기간의 옛 판에는 값이 있는 칸
-- = FR 정정이 새 판을 쌓으며 FS 컬럼을 떨어뜨린 자리(코드: FinancialWriter.writeOne 정정 갈래 · 이어받기 없음)
SELECT s.market_code, count(*) cells, count(DISTINCT l.stock_id) stocks,
       count(*) FILTER (WHERE l.fiscal_year >= 2021) fy2021plus,
       string_agg(DISTINCT s.ticker, ' ') FILTER (WHERE l.fiscal_year >= 2023) tickers_2023plus
FROM financial_statement l JOIN stock s ON s.id=l.stock_id
WHERE s.market_code IN ('KOSPI','KOSDAQ') AND l.is_latest AND l.operating_cash_flow IS NULL
  AND EXISTS (SELECT 1 FROM financial_statement o WHERE o.stock_id=l.stock_id AND o.fiscal_year=l.fiscal_year
              AND o.fiscal_period=l.fiscal_period AND NOT o.is_latest AND o.operating_cash_flow IS NOT NULL)
GROUP BY 1;
-- 대조군: 같은 술어를 미국에(미국은 FR 경로가 OCF를 채움 — 0이어야 기제가 국내 FS 몫임을 말함)
SELECT count(*) us_cells FROM financial_statement l JOIN stock s ON s.id=l.stock_id
WHERE s.market_code='US' AND l.is_latest AND l.operating_cash_flow IS NULL
  AND EXISTS (SELECT 1 FROM financial_statement o WHERE o.stock_id=l.stock_id AND o.fiscal_year=l.fiscal_year
              AND o.fiscal_period=l.fiscal_period AND NOT o.is_latest AND o.operating_cash_flow IS NOT NULL);
