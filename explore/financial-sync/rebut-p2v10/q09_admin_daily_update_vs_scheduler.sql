-- 관리자 일일 갱신(v10: 끝에 ⑧을 부름)과 사슬의 겹침 · 관리자 daily-update 이력 전체
set timezone='Asia/Seoul';
select a.id, a.job_name, a.status, a.started_at::timestamp(0) st, a.finished_at::timestamp(0) fin, a.target_date, a.retried_from_id,
  (select string_agg(b.job_name||'#'||b.id, ' ') from batch_execution b where b.triggered_by in ('SCHEDULER','GAP_FILL')
     and b.started_at < coalesce(a.finished_at, now()) and coalesce(b.finished_at, now()) > a.started_at) overlapping_scheduler
from batch_execution a where a.triggered_by='ADMIN' and a.job_name='daily-update' order by a.id;
