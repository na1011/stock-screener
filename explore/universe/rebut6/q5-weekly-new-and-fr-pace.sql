SET statement_timeout='60s';
-- (1) 주별 새 편입 행 — KR·US (재작성 주 포함)
SELECT effective_from, sum((universe_code<>'US_TOP1000')::int) kr, sum((universe_code='US_TOP1000')::int) us
FROM universe_membership WHERE universe_code IN ('KOSPI_TOP200','KOSDAQ_TOP150','US_TOP1000')
GROUP BY 1 HAVING sum((universe_code<>'US_TOP1000')::int) > 10 OR sum((universe_code='US_TOP1000')::int) > 40 ORDER BY 1;
-- (2) 국내 FR 종목당 소요 → DART 100ms 리미터 상한으로 본 종목당 콜 상한 · FSR 실제콜/종목
SELECT id, job_name, target_count, extract(epoch FROM finished_at-started_at)::int sec,
       round(extract(epoch FROM finished_at-started_at)::numeric / target_count / 0.1, 1) calls_upper_per_stock,
       substring(note from '실제콜=(\d+)')::int actual_calls,
       round(substring(note from '실제콜=(\d+)')::numeric / target_count, 1) fsr_calls_per_stock
FROM batch_execution WHERE id IN (645, 646, 658, 648, 663, 664) ORDER BY id;
