-- 국내 현 편입 최신본(FY2024~): 전체 재무제표(FSR)만 채우는 컬럼의 기간별 보유 — FR(요약본)이 새 행을 넣으면 FSR 전까지 빈다
SELECT f.fiscal_period, count(*) rows_,
  count(*) FILTER (WHERE f.total_equity_controlling IS NOT NULL) teq_c,
  count(*) FILTER (WHERE f.net_income_controlling IS NOT NULL) ni_c,
  count(*) FILTER (WHERE f.capex IS NOT NULL) capex,
  count(*) FILTER (WHERE f.inventories IS NOT NULL) inv,
  count(*) FILTER (WHERE f.total_equity IS NOT NULL) teq
FROM financial_statement f JOIN stock s ON s.id=f.stock_id
JOIN universe_membership m ON m.stock_id=s.id AND m.effective_to IS NULL AND m.universe_code IN ('KOSPI_TOP200','KOSDAQ_TOP150')
WHERE f.is_latest AND f.fiscal_year>=2024 GROUP BY 1 ORDER BY 1;
