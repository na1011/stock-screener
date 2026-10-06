-- SCHEDULER daily-update가 없던 평일(rebut-p2v9 q04: 08-11 · 08-17 · 08-21 · 09-16)에 앱 JVM이 떠 있던 흔적(그날 SCHEDULER·ADMIN 행)
-- v10 「오늘 사슬 미시작」 경보는 ⑨(15:30)가 그 JVM에서 돌아야 난다 — JVM이 종일 없으면 그날 경보 0 · 다음 날 ⑨는 「오늘」만 본다.
set timezone='Asia/Seoul';
select d::date as dd,
  (select string_agg(job_name||'#'||id||'@'||to_char(started_at,'HH24:MI')||'/'||triggered_by, ' ' order by id) from batch_execution b
    where b.started_at::date = d::date and b.triggered_by in ('SCHEDULER','ADMIN','GAP_FILL')) app_rows,
  (select count(*) from batch_execution b where b.started_at::date = d::date and b.triggered_by='MANUAL') manual_rows
from unnest(array['2026-08-11','2026-08-17','2026-08-21','2026-09-16']::date[]) d order by 1;
