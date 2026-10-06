-- 결정 2: 사슬 머리(13:30 · 15:00 · 17:00)에 적재 JVM이 살아 있던 평일 — 운영 DB의 MANUAL 실행 중 정상 종료한 것(유령 제외:
-- status RUNNING · ABORTED는 프로세스 생존을 보증 못 해 뺀다)으로 하한을 센다. 같은 JVM의 연속 잡은 행 사이 틈이 있어 하한이다.
set timezone='Asia/Seoul';
with m as (
  select id, job_name, started_at, finished_at, status from batch_execution
  where triggered_by='MANUAL' and status in ('SUCCESS','PARTIAL_SUCCESS','FAILED') and finished_at is not null
    and started_at >= now() - interval '60 days'
), days as (
  select d::date d from generate_series((now() - interval '60 days')::date, now()::date, interval '1 day') d
  where extract(isodow from d) <= 5
), fires as (
  select d, f, (d + f)::timestamptz t from days, (values (time '13:30'), (time '15:00'), (time '17:00')) v(f)
)
select fires.d, to_char(fires.f,'HH24:MI') fire, string_agg(m.id || ':' || m.job_name || ' ' || to_char(m.started_at,'HH24:MI') || '~' || to_char(m.finished_at,'HH24:MI'), ', ') alive
from fires join m on m.started_at < fires.t and m.finished_at > fires.t
group by 1,2 order by 1,2;
