-- B-1/B-5 국내: 요약본(FR)이 낸 행의 영업현금흐름은 전체 재무제표(FSR)가 채운다 — 최신본 기준 기간별 OCF 보유
SELECT s.ticker, f.fiscal_year, f.fiscal_period, f.revision_no, f.published_at, f.operating_cash_flow IS NOT NULL has_ocf, f.created_at::timestamp(0)
FROM financial_statement f JOIN stock s ON s.id=f.stock_id
WHERE s.ticker IN ('005930','077360') AND f.is_latest AND f.fiscal_year>=2025 ORDER BY 1,2,3;
-- 국내 현 편입 최신본 FY/분기별 OCF 보유율(FY2024~)
SELECT f.fiscal_period, count(*) rows_, count(*) FILTER (WHERE f.operating_cash_flow IS NOT NULL) with_ocf
FROM financial_statement f JOIN stock s ON s.id=f.stock_id
JOIN universe_membership m ON m.stock_id=s.id AND m.effective_to IS NULL AND m.universe_code IN ('KOSPI_TOP200','KOSDAQ_TOP150')
WHERE f.is_latest AND f.fiscal_year>=2024 GROUP BY 1 ORDER BY 1;
