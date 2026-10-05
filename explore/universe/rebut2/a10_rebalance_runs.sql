-- 리밸런싱 실행 이력: 같은 대상일 재실행 수 · 트리거 · 끝날 때 다른 배치가 RUNNING이었는가(꼬리 차단 근사)
SELECT r.id, r.target_date, r.triggered_by tb, r.status, r.started_at::timestamp(0) st, r.finished_at::timestamp(0) fin, r.written_row_count chg,
  count(*) OVER (PARTITION BY r.target_date) runs_same_target,
  (SELECT string_agg(o.job_name||'#'||o.id||'('||o.triggered_by||')', ',') FROM batch_execution o
    WHERE o.id<>r.id AND o.job_name NOT IN ('universe-rebalance') AND o.started_at <= r.finished_at
      AND (o.finished_at IS NULL OR o.finished_at > r.finished_at)) overlapping_at_finish,
  (SELECT string_agg(DISTINCT g.job_name, ',') FROM batch_execution g WHERE g.triggered_by='GAP_FILL' AND g.started_at BETWEEN r.finished_at AND r.finished_at + interval '2 hours') tail_jobs
FROM batch_execution r WHERE r.job_name='universe-rebalance' AND r.started_at > '2026-08-25' ORDER BY r.started_at;
