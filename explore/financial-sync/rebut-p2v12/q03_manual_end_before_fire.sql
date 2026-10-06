-- 결정 2(A) 「장부의 그 JVM 최근 영속 시각 5분」 + 「잡 끝·종료 훅에 영속」: 끝난 JVM이 5분 동안 산 것으로 보인다.
-- 평일 13:30·15:00·17:00 발화 직전 5분 안에 끝난 MANUAL 실행(DART를 쓸 수 있는 잡) — 그 발화의 DART 단계가 거짓으로 미뤄졌을 칸.
set timezone='Asia/Seoul';
with fires as (
  select (d + t)::timestamptz at time zone 'Asia/Seoul' as fire_naive, d, t
  from generate_series(date '2026-08-01', date '2026-10-06', interval '1 day') d,
       (values (time '13:30'),(time '15:00'),(time '17:00')) v(t)
  where extract(isodow from d) < 6
)
select f.d::date, f.t, e.id, e.job_name, e.finished_at
from fires f join batch_execution e
  on e.triggered_by='MANUAL'
 and e.job_name in ('financial-refill','full-statement-refill','governance-load','corporate-action','company-profile','initial-load')
 and e.finished_at >= ((f.d::date + f.t) - interval '5 minutes') at time zone 'Asia/Seoul'
 and e.finished_at <  (f.d::date + f.t) at time zone 'Asia/Seoul'
order by 1,2;
