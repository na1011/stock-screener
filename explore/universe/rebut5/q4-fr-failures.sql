SET statement_timeout='60s';
-- FR·FSR 실패 이력 중 지금 편입(활성 유니버스 열린 행)인 종목 — 영구 대상(상한 칸 점유) 후보
SELECT e.job_name, f.execution_id, s.ticker, s.market_code, f.failure_kind, left(f.message,90) msg, f.resolved,
       EXISTS (SELECT 1 FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
               WHERE m.stock_id=s.id AND m.effective_to IS NULL) cur_member
FROM batch_execution_failure f JOIN batch_execution e ON e.id=f.execution_id JOIN stock s ON s.id=f.stock_id
WHERE e.job_name IN ('financial-refill','full-statement-refill') AND e.started_at > '2026-09-14'
ORDER BY cur_member DESC, f.execution_id DESC LIMIT 40;
