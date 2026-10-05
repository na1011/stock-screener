-- A R5: 「US_TOP1000만 10-02 단계 되돌리기」가 건드릴 행 — 10-02에 연 행(지울 것) · 10-02로 닫은 행(다시 열 것)
SELECT 'opened_1002' kind, m.origin, count(*), min(m.created_at)::timestamp(0), max(m.created_at)::timestamp(0)
FROM universe_membership m WHERE m.universe_code='US_TOP1000' AND m.effective_from='2026-10-02' GROUP BY 1,2
UNION ALL
SELECT 'closed_1002', m.origin, count(*), min(m.created_at)::timestamp(0), max(m.created_at)::timestamp(0)
FROM universe_membership m WHERE m.universe_code='US_TOP1000' AND m.effective_to='2026-10-02' GROUP BY 1,2;
-- 그 단계에서 처음 생긴 stock 행(되돌리기 밖에 남는 것) · 같은 날 다른 유니버스의 10-02 행
SELECT 'stock_created_1003' kind, count(*) FROM stock WHERE created_at >= '2026-10-03 19:00+09' AND created_at < '2026-10-04 00:00+09';
SELECT universe_code, count(*) FILTER (WHERE effective_from='2026-10-02') opened, count(*) FILTER (WHERE effective_to='2026-10-02') closed
FROM universe_membership GROUP BY 1 ORDER BY 1;
-- 10-03 실행 기록(리밸런싱 + 꼬리)
SELECT id, job_name, triggered_by, status, target_count, started_at::timestamp(0), left(note,90) FROM batch_execution
WHERE started_at >= '2026-10-03 19:00+09' AND started_at < '2026-10-04 06:00+09' ORDER BY id;
