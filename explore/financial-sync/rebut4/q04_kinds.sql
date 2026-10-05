-- 0콜 · 운영 SELECT: 실패 종류 × 잡 × 실행 상태 (전 이력) — 소스 단위 실패(RATE_LIMITED)가 성공 상태 실행에 있었나
SELECT e.job_name, e.status, f.failure_kind, count(DISTINCT e.id) runs, count(*) rows, max(e.target_count) max_tc
FROM batch_execution e JOIN batch_execution_failure f ON f.execution_id=e.id
GROUP BY 1,2,3 ORDER BY 1,2,3;
