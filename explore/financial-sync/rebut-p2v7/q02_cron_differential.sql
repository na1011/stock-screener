-- 0콜 · SELECT만. 평일마다 SCHEDULER 첫 발화 시각(13:30 일일 갱신 · 14:00 기업 행위)과 명목 대비 지연(분),
-- 그리고 두 지연의 차(= 13:30 끝 ~ 14:00 사이에 잔 시간 · 풀 1 대기). 차가 크면 「크론 사이 수면」의 실례다.
with d as (
  select (started_at at time zone 'Asia/Seoul')::date kd, job_name,
         min(started_at at time zone 'Asia/Seoul') st,
         min(finished_at at time zone 'Asia/Seoul') fin
  from batch_execution where triggered_by='SCHEDULER' and job_name in ('daily-update','corporate-action')
  group by 1,2
)
select a.kd, to_char(a.kd,'Dy') dow,
       to_char(a.st,'HH24:MI:SS') du_start, to_char(a.fin,'HH24:MI:SS') du_end,
       round(extract(epoch from a.st - (a.kd + time '13:30'))/60) du_delay_min,
       to_char(b.st,'HH24:MI:SS') ca_start,
       round(extract(epoch from b.st - (b.kd + time '14:00'))/60) ca_delay_min,
       round(extract(epoch from (b.st - (b.kd + time '14:00')) - (a.st - (a.kd + time '13:30')))/60) diff_min,
       (a.fin > a.kd + time '14:00') du_ran_past_1400
from d a left join d b on b.kd=a.kd and b.job_name='corporate-action'
where a.job_name='daily-update'
order by a.kd;
