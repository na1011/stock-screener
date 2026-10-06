-- MANUAL 유령 RUNNING(부트스트랩 JVM이 죽어 남은 행) — 다음 bootRun까지 RUNNING으로 남는다(BatchExecutionSweeper.sweepBootstrapOrphans).
-- v10 결정 2 「MANUAL DART 잡이 RUNNING이면 단계를 미룬다」가 이 행에 걸리면 단계가 그 사이 매 발화 미뤄진다.
set timezone='Asia/Seoul';
select id, job_name, status, started_at::timestamp(0), coalesce(progress_at, started_at)::timestamp(0) last_progress,
       finished_at::timestamp(0) closed_at,
       round(extract(epoch from finished_at - coalesce(progress_at, started_at))/3600.0, 1) ghost_hours
from batch_execution
where triggered_by = 'MANUAL' and (note like '%프로세스가 사라져%' or status = 'RUNNING')
order by id;
select 'MANUAL RUNNING now', count(*) from batch_execution where triggered_by='MANUAL' and status='RUNNING';
