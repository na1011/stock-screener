-- 0콜 · 운영 SELECT: BUDGETS 잡 전부 · 성공 상태 실행에서 실패율(fc/tc) 분포와 시장별 실패 행
SELECT e.job_name, e.triggered_by, count(*) runs,
       sum((e.failure_count>0)::int) runs_with_fail,
       max(round(100.0*e.failure_count/nullif(e.target_count,0),1)) max_fail_pct,
       sum((e.failure_count*2 >= e.target_count AND e.target_count>0)::int) runs_fail_ge_half,
       sum((e.target_count=0)::int) zero_target
FROM batch_execution e
WHERE e.status IN ('SUCCESS','PARTIAL_SUCCESS') AND e.started_at >= '2026-08-31'
GROUP BY 1,2 ORDER BY 1,2;
