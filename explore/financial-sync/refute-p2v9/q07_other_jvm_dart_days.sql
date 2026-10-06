-- 앱 밖 JVM(MANUAL = bootRun)이 DART를 쓸 수 있는 잡을 돌린 평일 — 앱 리미터의 usedToday가 못 보는 같은 날 DART 사용의 하한 재료
-- (실제 DART 콜 수는 실행 기록에 없다 — v9 결정 3이 그 칸을 새로 만든다. 여기서는 「그런 날이 있었나」만 센다)
select (started_at at time zone 'Asia/Seoul')::date d, string_agg(job_name||'#'||id||'('||target_count||')', ' ' order by id) runs
from batch_execution
where triggered_by='MANUAL' and job_name in ('financial-refill','full-statement-refill','governance-load','corporate-action','company-profile')
  and started_at >= '2026-09-01' and extract(isodow from (started_at at time zone 'Asia/Seoul')) between 1 and 5
group by 1 order by 1;
