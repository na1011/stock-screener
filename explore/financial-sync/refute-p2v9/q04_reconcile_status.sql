-- 화해·사슬 안 백필의 상태 이력(⑦ 실패 → ⑧ 갈래의 빈도 재료) · 무효화를 낸 재구축·FR
select id, job_name, triggered_by, status, target_date, (started_at at time zone 'Asia/Seoul')::timestamp(0) kst, target_count, failure_count, left(coalesce(note,''),90) note
from batch_execution
where job_name in ('indicator-backfill','indicator-origin-rewrite','indicator-derived-rebuild') and triggered_by in ('SCHEDULER','GAP_FILL')
order by started_at;
select status, count(*) from batch_execution where job_name in ('indicator-backfill','indicator-origin-rewrite','indicator-derived-rebuild') group by 1;
