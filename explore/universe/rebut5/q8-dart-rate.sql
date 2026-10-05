SET statement_timeout='60s';
-- 국내 FSR·FR 종목당 실제 콜·소요(분기 포함 8년 · SUCCESS)
SELECT id, job_name, target_count tc, (substring(note from '실제콜=(\d+)'))::int calls,
       round((substring(note from '실제콜=(\d+)'))::numeric / target_count, 1) per_stock,
       round(extract(epoch from finished_at-started_at)::numeric / target_count, 2) sec_per_stock
FROM batch_execution WHERE status='SUCCESS' AND target_count>0 AND (
  (job_name='full-statement-refill' AND note LIKE '%Q1%' AND note LIKE '%2018~2026%')
  OR id IN (645,646,658,683,653)) ORDER BY job_name, id;
