-- SCHEDULER 첫 잡(daily-update · corporate-action · universe-rebalance)의 실제 시작 시각(KST)과 명목 대비 지연 — 전 기간
select job_name, (started_at at time zone 'Asia/Seoul')::timestamp(0) st_kst,
       extract(isodow from started_at at time zone 'Asia/Seoul') dow,
       round(extract(epoch from (started_at at time zone 'Asia/Seoul')::time - case job_name when 'daily-update' then time '13:30' when 'corporate-action' then time '14:00' else time '20:00' end)/60) late_min
from batch_execution
where triggered_by='SCHEDULER' and job_name in ('daily-update','corporate-action','universe-rebalance')
  and abs(extract(epoch from (started_at at time zone 'Asia/Seoul')::time - case job_name when 'daily-update' then time '13:30' when 'corporate-action' then time '14:00' else time '20:00' end)) > 120
order by started_at;
select job_name, count(*) total from batch_execution where triggered_by='SCHEDULER' and job_name in ('daily-update','corporate-action','universe-rebalance') group by 1;
