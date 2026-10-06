-- 「비사슬 DART 잡」이 언제 시작했나: KST 요일 · 시각대(13:00 전/뒤) · 08-10 뒤 전부
-- DART 잡 = 국내를 포함할 수 있는 잡 이름(financial-refill · full-statement-refill · governance-load · corporate-action · company-profile)
SELECT job_name,
       triggered_by,
       CASE WHEN extract(isodow FROM started_at AT TIME ZONE 'Asia/Seoul') IN (6,7) THEN 'weekend' ELSE 'weekday' END AS daytype,
       CASE WHEN (started_at AT TIME ZONE 'Asia/Seoul')::time < '13:00' THEN 'before13' ELSE 'after13' END AS slot,
       count(*) AS runs,
       sum(target_count) AS targets
FROM batch_execution
WHERE job_name IN ('financial-refill','full-statement-refill','governance-load','corporate-action','company-profile')
  AND started_at >= '2026-08-10'
GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
