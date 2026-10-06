-- 평일(KST) 00:00~13:30에 시작한 MANUAL/ADMIN DART 사용 잡 — 앱 JVM의 usedToday가 못 보는 그날 DART 사용(대상 수만 · 콜 수는 기록 없음)
select id, job_name, triggered_by, status, (started_at at time zone 'Asia/Seoul')::timestamp(0) st_kst,
       round(extract(epoch from finished_at-started_at)/60,1) min, target_count
from batch_execution
where triggered_by in ('MANUAL','ADMIN')
  and job_name in ('financial-refill','full-statement-refill','governance-load','corporate-action','company-profile')
  and extract(isodow from started_at at time zone 'Asia/Seoul') <= 5
  and (started_at at time zone 'Asia/Seoul')::time < time '13:30'
  and started_at > now() - interval '60 days'
order by started_at;
