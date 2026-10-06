-- 최근 60일(09-06~10-06) MANUAL(bootRun) 실행의 시작 시각 분포 — v10 결정 3의 손 쓰기 문이 닫는 창
-- 평일: [00:00,13:30) = 「오늘 13:30 사슬의 끝 줄 뒤」가 원리적으로 거짓인 창(사슬 전) · [13:30,17:00) = 사슬·15:00·15:30·17:00 명목 사이 · [17:00,24:00)
-- 주말은 따로. 잡 이름 · 소요(분) 최대도 함께. GAP_FILL 꼬리는 MANUAL 리밸런싱의 꼬리라 빼지 않고 트리거로만 거른다.
with m as (
  select id, job_name, started_at at time zone 'Asia/Seoul' st, finished_at at time zone 'Asia/Seoul' fin,
         extract(isodow from started_at at time zone 'Asia/Seoul') dow
  from batch_execution
  where triggered_by='MANUAL' and started_at >= now() - interval '60 days'
)
select case when dow>=6 then '주말'
            when st::time < '06:00' then '평일 00:00-06:00'
            when st::time < '13:30' then '평일 06:00-13:30'
            when st::time < '17:00' then '평일 13:30-17:00'
            else '평일 17:00-24:00' end win,
       count(*) runs, count(distinct st::date) days,
       round(max(extract(epoch from fin-st))/60) max_min,
       string_agg(distinct job_name, ',') jobs
from m group by 1 order by 1;
