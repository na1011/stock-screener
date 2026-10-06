-- 60일 daily-update 실행별 · 시장(KR=KOSPI/KOSDAQ · US=그 외)별 실패 수 · 시장 통째 실패에 가까운 실행
select e.id, e.triggered_by, e.status, (e.started_at at time zone 'Asia/Seoul')::date d, e.target_date, e.target_count, e.success_count, e.failure_count,
  count(*) filter (where s.market_code in ('KOSPI','KOSDAQ')) kr_fail,
  count(*) filter (where s.market_code not in ('KOSPI','KOSDAQ')) us_fail,
  string_agg(distinct f.failed_phase||'/'||f.failure_kind, ',') kinds
from batch_execution e join batch_execution_failure f on f.execution_id=e.id join stock s on s.id=f.stock_id
where e.job_name='daily-update' and e.started_at > now()-interval '60 days'
group by 1,2,3,4,5,6,7,8 order by greatest(count(*) filter (where s.market_code in ('KOSPI','KOSDAQ')), count(*) filter (where s.market_code not in ('KOSPI','KOSDAQ'))) desc limit 12;
