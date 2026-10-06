-- 최근 60일 평일에 MANUAL 실행(운영 DB에 쓴 적재 JVM)이 [13:30, 17:00] KST 창과 겹친 날 · 13:30을 덮은 날 · 17:00을 덮은 날
-- (v11 결정 2: 사슬 머리에서 적재 JVM이 살아 있으면 사슬 전체를 다음 발화로 · 17:00에도 살아 있으면 단계 미룸)
-- 운영 DB 행만 센다 — 사본 드라이런(DB_URL 환경변수)은 이 DB에 행이 없어 하한이다.
with m as (
  select id, job_name, started_at at time zone 'Asia/Seoul' s, coalesce(finished_at, progress_at, started_at) at time zone 'Asia/Seoul' f, status
  from batch_execution where triggered_by='MANUAL' and started_at >= now() - interval '60 days'
), d as (
  select g::date d from generate_series((now() at time zone 'Asia/Seoul')::date - 60, (now() at time zone 'Asia/Seoul')::date, interval '1 day') g
  where extract(isodow from g) <= 5
)
select d.d,
  string_agg(distinct m.job_name||'#'||m.id||'('||to_char(m.s,'HH24:MI')||'-'||to_char(m.f,'MM-DD HH24:MI')||' '||m.status||')', ', ') jobs,
  bool_or(m.s <= d.d + time '13:30' and m.f >= d.d + time '13:30') covers_1330,
  bool_or(m.s <= d.d + time '15:00' and m.f >= d.d + time '15:00') covers_1500,
  bool_or(m.s <= d.d + time '17:00' and m.f >= d.d + time '17:00') covers_1700
from d join m on m.s < d.d + time '17:00' and m.f > d.d + time '13:30'
group by d.d order by d.d;
