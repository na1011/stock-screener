-- financial-refill 실행 — 종목당 소요(초)와 무효화 비율. note의 무효화 수를 뽑는다.
SELECT id, started_at::timestamp(0), triggered_by tb, status, target_count n, failure_count f,
  round(extract(epoch FROM finished_at-started_at)::numeric,0) sec,
  round((extract(epoch FROM finished_at-started_at)/nullif(target_count,0))::numeric,2) sec_per,
  substring(note from '정정=(\d+)') rev, substring(note from '교정=(\d+)') cor, substring(note from '채움=(\d+)') fil,
  substring(note from '무효화=(\d+)종목') inval
FROM batch_execution WHERE job_name='financial-refill' AND started_at > '2026-09-01'
ORDER BY started_at;
