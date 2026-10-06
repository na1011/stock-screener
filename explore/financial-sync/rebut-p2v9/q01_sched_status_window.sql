-- SCHEDULER(+GAP_FILL) 실행의 상태 · 시작 시각(KST) — 평일 13:00~17:00 창에서 끝이 SUCCESS가 아닌 것 · 전 기간
select id, job_name, triggered_by, status, (started_at at time zone 'Asia/Seoul')::timestamp(0) st_kst,
       (finished_at at time zone 'Asia/Seoul')::timestamp(0) fin_kst, target_count, failure_count, left(coalesce(note,''),80) note
from batch_execution
where triggered_by in ('SCHEDULER','GAP_FILL')
  and status not in ('SUCCESS')
order by started_at;
