-- 08-10~10-06 평일 중 SCHEDULER daily-update가 그 날짜(KST)에 시작하지 않은 날 — 그날의 판정 D(targetDate)는 워치리스트가 한 번도 안 본다
with d as (select g::date d from generate_series(date '2026-08-10', date '2026-10-06', interval '1 day') g where extract(isodow from g) <= 5),
r as (select (started_at at time zone 'Asia/Seoul')::date d, count(*) n from batch_execution where job_name='daily-update' and triggered_by='SCHEDULER' group by 1)
select count(*) weekdays, count(*) filter (where r.n is null) no_run, string_agg(case when r.n is null then to_char(d.d,'MM-DD') end, ' ' order by d.d) days
from d left join r on r.d = d.d;
