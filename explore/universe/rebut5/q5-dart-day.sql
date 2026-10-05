SET statement_timeout='60s';
-- 평일 DART 소비 잡의 최근 note(콜 수가 적혀 있는지) — corporate-action · governance-load
SELECT id, job_name, triggered_by, to_char(started_at AT TIME ZONE 'Asia/Seoul','MM-DD HH24:MI') st, target_count tc, left(note,200) note
FROM batch_execution WHERE job_name IN ('corporate-action','governance-load') AND started_at > '2026-09-25' ORDER BY id DESC LIMIT 12;
