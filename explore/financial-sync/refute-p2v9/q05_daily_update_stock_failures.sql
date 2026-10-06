-- 일일 갱신이 종목 단위로 실패한 날(그 종목은 D 값이 없어 워치리스트가 「판정불가 → 불충족」으로 접는다 — ADR-0026의 꼴)
select id, triggered_by, status, target_date, (started_at at time zone 'Asia/Seoul')::timestamp(0) kst, target_count, failure_count
from batch_execution where job_name='daily-update' and (failure_count > 0 or status <> 'SUCCESS') order by started_at;
select count(*) filter (where failure_count>0) as runs_with_stock_failures, count(*) as runs from batch_execution where job_name='daily-update' and triggered_by='SCHEDULER';
