-- 무거운 잡의 첫 정기 실행일 — JobFreshnessPolicy.lastSuccessAt 폴백 술어를 그대로(SCHEDULER 성공 0 → GAP_FILL 뺀 SUCCESS·PARTIAL) ·
-- 0부(V63) 뒤에도 scope IS NULL 이력은 센다(ADR-0172) · isOverdue = Duration(last, 그날 14:15).toDays() > 25 ·
-- heavyJobDueToday = 배정 요일(화 G · 수 FSR · 목 FR) 먼저, 없으면 가장 밀린 것 · 하루 하나 · 그 사이 다른 실행 없음으로 놓는다.
with recursive last0 as (
  select job_name, max(started_at) last from batch_execution
  where job_name in ('governance-load','full-statement-refill','financial-refill') and status in ('SUCCESS','PARTIAL_SUCCESS')
    and triggered_by <> 'GAP_FILL' group by 1),
days as (select d::date d from generate_series(date '2026-10-07', date '2026-12-15', interval '1 day') d where extract(isodow from d) <= 5),
sim(d, g, fsr, fr, ran) as (
  select date '2026-10-06', (select last from last0 where job_name='governance-load'), (select last from last0 where job_name='full-statement-refill'), (select last from last0 where job_name='financial-refill'), null::text
  union all
  select n.d,
    case when pick='G' then (n.d + time '14:15') at time zone 'Asia/Seoul' else s.g end,
    case when pick='FSR' then (n.d + time '14:15') at time zone 'Asia/Seoul' else s.fsr end,
    case when pick='FR' then (n.d + time '14:15') at time zone 'Asia/Seoul' else s.fr end,
    pick
  from sim s
  join lateral (select min(d) d from days where days.d > s.d) n on n.d is not null
  cross join lateral (
    select extract(day from ((n.d + time '14:15') at time zone 'Asia/Seoul') - s.g)::int gd,
           extract(day from ((n.d + time '14:15') at time zone 'Asia/Seoul') - s.fsr)::int fd,
           extract(day from ((n.d + time '14:15') at time zone 'Asia/Seoul') - s.fr)::int rd,
           extract(isodow from n.d)::int dow) x
  cross join lateral (select case
      when dow=2 and gd>25 then 'G' when dow=3 and fd>25 then 'FSR' when dow=4 and rd>25 then 'FR'
      when greatest(case when gd>25 then gd else -1 end, case when fd>25 then fd else -1 end, case when rd>25 then rd else -1 end) < 0 then null
      when gd>25 and gd >= greatest(case when fd>25 then fd else -1 end, case when rd>25 then rd else -1 end) then 'G'
      when fd>25 and fd >= case when rd>25 then rd else -1 end then 'FSR'
      else 'FR' end as pick) p
)
select 'last0', job_name, to_char(last,'YYYY-MM-DD HH24:MI') from last0
union all
select 'run', ran, to_char(d,'YYYY-MM-DD Dy') from sim where ran is not null;
