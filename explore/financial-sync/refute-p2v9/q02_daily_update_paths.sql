-- daily-update 실행 경로별(SCHEDULER · ADMIN) 날짜·시각 — ADMIN 경로가 워치리스트를 판정하던 실제 사용 이력
select id, triggered_by, status, target_date, (started_at at time zone 'Asia/Seoul')::timestamp(0) as kst_start,
       (finished_at at time zone 'Asia/Seoul')::timestamp(0) as kst_end, retried_from_id, target_count, failure_count
from batch_execution where job_name='daily-update' and (triggered_by<>'SCHEDULER' or started_at < '2026-09-02')
order by started_at;
-- 평일 중 SCHEDULER daily-update가 없는 날(08-10~10-06)
select g::date as weekday_without_scheduler_du
from generate_series('2026-08-10'::date,'2026-10-06'::date,'1 day') g
where extract(isodow from g) between 1 and 5
  and not exists (select 1 from batch_execution b where b.job_name='daily-update' and b.triggered_by='SCHEDULER'
                  and (b.started_at at time zone 'Asia/Seoul')::date = g::date)
order by 1;
