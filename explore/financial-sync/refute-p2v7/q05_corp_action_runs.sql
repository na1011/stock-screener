-- 14:00 기업 행위 사슬의 최근 실행 — v7에서 단계(12:30) 뒤로 밀리는 DART 소비자. 콜 수는 실행 기록에 없다(PipelineScheduler:267 javadoc 「700」이 출처).
select id, triggered_by, status, started_at at time zone 'Asia/Seoul' st, finished_at-started_at dur, target_count, failure_count
from batch_execution where job_name='corporate-action' and started_at > now() - interval '25 days' order by id desc limit 12;
