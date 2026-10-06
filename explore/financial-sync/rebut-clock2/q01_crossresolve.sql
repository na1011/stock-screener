-- 실패를 낸 잡별 · 단계별 · 해결 여부
SELECT e.job_name, f.failed_phase, s.market_code, f.resolved, count(*)
FROM batch_execution_failure f JOIN batch_execution e ON e.id=f.execution_id JOIN stock s ON s.id=f.stock_id
WHERE e.job_name IN ('full-statement-refill','financial-refill','governance-load','company-profile','corporate-action','governance-derive')
GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
-- FSR #410/#415의 미국 NOT_FOUND: 해결 여부 (FSR은 미국 소스가 없으므로 FSR 자신은 해결 못 함)
SELECT f.execution_id, s.market_code, f.failure_kind, f.resolved, count(*)
FROM batch_execution_failure f JOIN stock s ON s.id=f.stock_id
WHERE f.execution_id IN (410,415) GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
-- 그 뒤 FINANCIAL 단계를 미국 종목에서 성공한 잡 실행 (후보 해결자)
SELECT id, job_name, triggered_by, status, started_at, target_count, success_count, failure_count
FROM batch_execution WHERE job_name IN ('financial-refill','initial-load','full-statement-refill') AND started_at > (SELECT started_at FROM batch_execution WHERE id=410)
ORDER BY started_at LIMIT 40;
