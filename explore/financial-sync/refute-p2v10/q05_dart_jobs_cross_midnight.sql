-- DART를 쓸 수 있는 잡 중 KST 자정을 넘긴 실행 — v10 결정 2의 「실행 기록 DART 콜 합(usedToday 차)」이 날짜를 잘못 귀속하는 모집단
-- (TokenBucketRateLimiter는 날짜가 바뀌면 usedToday를 0으로 리셋한다 — 자정을 넘긴 잡의 끝−시작 차는 그날 콜이 아니다)
select triggered_by, job_name, count(*) runs,
       count(*) filter (where (started_at at time zone 'Asia/Seoul')::date <> (finished_at at time zone 'Asia/Seoul')::date) cross_midnight,
       string_agg(case when (started_at at time zone 'Asia/Seoul')::date <> (finished_at at time zone 'Asia/Seoul')::date
                       then '#'||id||' '||to_char(started_at at time zone 'Asia/Seoul','MM-DD HH24:MI')||'→'||to_char(finished_at at time zone 'Asia/Seoul','HH24:MI') end, ' ' order by id) which
from batch_execution
where job_name in ('financial-refill','full-statement-refill','governance-load','corporate-action','company-profile','initial-load')
  and started_at >= '2026-08-07' and finished_at is not null
group by 1,2 order by 1,2;
