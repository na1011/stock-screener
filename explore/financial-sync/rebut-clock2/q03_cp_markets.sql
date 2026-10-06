-- CP 기본 대상(업종 빈 편입 종목)의 시장별 수 — 지금
SELECT s.market_code, count(DISTINCT m.stock_id)
FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active
WHERE m.effective_to IS NULL AND s.is_active AND s.industry_code IS NULL AND s.sic_code IS NULL
GROUP BY 1;
SELECT s.market_code, s.ticker, s.name FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active
WHERE m.effective_to IS NULL AND s.is_active AND s.industry_code IS NULL AND s.sic_code IS NULL ORDER BY 1,2;
-- CP 정기 실행 결과
SELECT id, triggered_by, started_at, target_count, success_count, failure_count, written_row_count, status FROM batch_execution WHERE job_name='company-profile' AND started_at > now()-interval '30 days' ORDER BY id;
