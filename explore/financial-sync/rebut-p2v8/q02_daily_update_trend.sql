select id, target_date, status, started_at at time zone 'Asia/Seoul' st, round(extract(epoch from finished_at-started_at)::numeric/60,1) min, target_count, failure_count
from batch_execution where job_name='daily-update' and triggered_by='SCHEDULER' and started_at > now()-interval '40 days' order by started_at;
