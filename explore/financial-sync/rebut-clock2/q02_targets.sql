-- 무거운 잡 다섯(계획이 고른)의 실행별 대상 수 분포(최근 60일 · 트리거별)
SELECT job_name, triggered_by, target_count, status, count(*), min(started_at)::date, max(started_at)::date
FROM batch_execution
WHERE job_name IN ('company-profile','corporate-action','governance-load','full-statement-refill','financial-refill')
  AND started_at > now() - interval '60 days'
GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
