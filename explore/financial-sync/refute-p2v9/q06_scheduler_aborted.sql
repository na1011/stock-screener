-- 앱 JVM 쓰기(SCHEDULER · GAP_FILL · ADMIN)가 종료·유령으로 끊긴 이력 — 한 메서드 안 중단 뒤 그날 나머지가 다시 안 도는 빈도의 재료
select id, job_name, triggered_by, status, (started_at at time zone 'Asia/Seoul')::timestamp(0) kst, (finished_at at time zone 'Asia/Seoul')::timestamp(0) fin, left(coalesce(note,''),80) note
from batch_execution where triggered_by in ('SCHEDULER','GAP_FILL','ADMIN') and status in ('ABORTED','FAILED') order by started_at;
