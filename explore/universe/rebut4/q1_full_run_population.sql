-- 계획 v4 B-1 초기값: 「전량 실행 이력의 그 시각 활성 편입」 재구성이 실행의 target_count와 맞는가
-- 재구성: 그 실행 시작 시각에 열려 있던 행 = created_at <= started_at AND effective_from <= started_at::date
--          AND (effective_to IS NULL OR effective_to > started_at::date)   (닫는 쓰기는 기록이 없으므로 날짜로)
WITH runs AS (SELECT id, started_at, target_count, success_count, failure_count FROM batch_execution WHERE id IN (643,645,646,653,655,658,683))
SELECT r.id, r.started_at::timestamp(0), r.target_count, r.success_count, r.failure_count, m.universe_code,
       count(DISTINCT m.stock_id) recon,
       count(DISTINCT m.stock_id) FILTER (WHERE s.is_active) recon_active_stock
FROM runs r
JOIN universe_membership m ON m.created_at <= r.started_at AND m.effective_from <= (r.started_at AT TIME ZONE 'Asia/Seoul')::date
     AND (m.effective_to IS NULL OR m.effective_to > (r.started_at AT TIME ZONE 'Asia/Seoul')::date)
JOIN universe u ON u.code=m.universe_code AND u.is_active
JOIN stock s ON s.id=m.stock_id
GROUP BY 1,2,3,4,5,6 ORDER BY 1,6;
-- 그 실행들의 note 앞부분 (유니버스 인자 단서)
SELECT id, triggered_by, target_count, left(note,60) FROM batch_execution WHERE id IN (643,645,646,653,655,658,683) ORDER BY id;
