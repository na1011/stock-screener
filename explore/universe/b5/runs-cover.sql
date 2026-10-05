-- B v5 초기값 재구성 — 「그 실행이 어느 활성 유니버스 전량을 덮었나」를 실행 시각의 편입으로 다시 짓는다.
-- 실행 이력에는 종목별 대상이 없다(batch_execution · _failure · _phase뿐) → target_count = 그 시각 열린 편입 수로 맞춘다.
-- 후보 실행: SUCCESS · 실패 0 · 연수 ≥ 8(note의 연도=A~B에서 B−A) · FSR은 분기 포함(note의 보고서에 Q1).
WITH runs AS (
  SELECT id, job_name, started_at, (started_at AT TIME ZONE 'Asia/Seoul')::date d, target_count,
         (substring(note from '연도=\d{4}~(\d{4})'))::int - (substring(note from '연도=(\d{4})~'))::int yrs
  FROM batch_execution
  WHERE job_name IN ('financial-refill','full-statement-refill') AND status = 'SUCCESS' AND failure_count = 0
    AND (job_name = 'financial-refill' OR note LIKE '%Q1%')),
open_at AS (
  SELECT r.id, m.universe_code, count(DISTINCT m.stock_id) n
  FROM runs r JOIN universe_membership m ON m.effective_from <= r.d AND (m.effective_to IS NULL OR m.effective_to > r.d)
    AND m.created_at <= r.started_at  -- 실행 뒤에 소급해 생긴 행(토요일 리밸런싱 · 복원)을 빼려고
  JOIN universe u ON u.code = m.universe_code AND u.is_active
  JOIN stock s ON s.id = m.stock_id AND s.is_active
  GROUP BY r.id, m.universe_code)
SELECT r.job_name, r.id, to_char(r.started_at AT TIME ZONE 'Asia/Seoul','MM-DD HH24:MI') st, r.yrs, r.target_count,
       string_agg(o.universe_code || '=' || o.n, ' ' ORDER BY o.universe_code) open_counts,
       coalesce((SELECT string_agg(o2.universe_code, ',') FROM open_at o2 WHERE o2.id = r.id AND o2.n = r.target_count),
                CASE WHEN r.target_count = (SELECT sum(n) FROM open_at o3 WHERE o3.id = r.id) THEN 'ALL' END) covers
FROM runs r LEFT JOIN open_at o ON o.id = r.id
WHERE r.yrs >= 8
GROUP BY r.job_name, r.id, r.started_at, r.yrs, r.target_count
ORDER BY r.job_name, r.id DESC;
