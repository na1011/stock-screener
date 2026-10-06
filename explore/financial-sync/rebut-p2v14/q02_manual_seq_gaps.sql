-- (B) 직렬화의 「살아 있는 손 실행」 술어는 행 단위다. 손 작업은 여러 잡의 연쇄(FR → 화해 등)이고 잡 사이에 틈이 있다.
-- 지난 60일 MANUAL/ADMIN 행에서 「앞 행 끝 → 다음 행 시작」 틈(같은 손 연쇄로 보는 기준 10분 안)의 분포.
set timezone='Asia/Seoul';
with m as (
  select id, job_name, triggered_by, started_at, finished_at,
         lag(finished_at) over (order by started_at) prev_fin, lag(job_name) over (order by started_at) prev_job
  from batch_execution where triggered_by in ('MANUAL','ADMIN') and started_at > now() - interval '60 days'
), g as (
  select *, extract(epoch from started_at - prev_fin) gap_s from m where prev_fin is not null
)
select case when gap_s < 0 then 'overlap' when gap_s < 60 then '<60s' when gap_s < 120 then '60-120s'
            when gap_s < 300 then '2-5m' when gap_s < 600 then '5-10m' else '>=10m' end bucket,
       count(*) n,
       count(*) filter (where extract(isodow from started_at) between 1 and 5) weekday_n
from g group by 1 order by min(gap_s);
-- 평일 13:00~23:00에 시작한 「틈 < 120초」 연쇄 쌍
select to_char(started_at,'MM-DD Dy HH24:MI:SS') st, prev_job, job_name, round(gap_s) gap_s
from g where gap_s between 0 and 120 and extract(isodow from started_at) between 1 and 5
  and extract(hour from started_at) between 13 and 22 order by started_at;
