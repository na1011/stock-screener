-- 결정 2는 사슬 머리에서만 적재 JVM 생존을 묻는다. 사슬이 도는 동안(머리 뒤) 새로 뜬 손 잡은 안 보인다.
-- 지난 60일 평일에 SCHEDULER 사슬 잡(13:30~그날 마지막 SCHEDULER 잡 끝)이 도는 사이 시작한 MANUAL 실행 — 하한(운영 DB만 · 사본 bootRun 제외).
set timezone='Asia/Seoul';
with s as (
  select started_at::date d, min(started_at) a, max(coalesce(finished_at, started_at)) b
  from batch_execution where triggered_by='SCHEDULER' and job_name <> 'universe-rebalance'
    and started_at >= now() - interval '60 days' and extract(isodow from started_at) <= 5
  group by 1
)
select s.d, to_char(s.a,'HH24:MI') chain_from, to_char(s.b,'HH24:MI') chain_to,
       string_agg(m.id || ':' || m.job_name || ' ' || to_char(m.started_at,'HH24:MI') || '~' || coalesce(to_char(m.finished_at,'HH24:MI'),'?') || ' ' || m.status, ', ' order by m.id) manual
from s join batch_execution m on m.triggered_by='MANUAL' and m.started_at > s.a and m.started_at < s.b
group by 1,2,3 order by 1;
