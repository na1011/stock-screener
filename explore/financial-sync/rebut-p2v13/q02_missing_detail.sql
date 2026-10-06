-- q01에서 「D 봉 없음」으로 남은 (실행, 시장, 종목)을 펼친다 — 그 종목이 그때 ③의 모집단(effective_to IS NULL 근사 불가 → 지금 표 기준)인지,
-- 봉이 언제 왔는지, 실패 행이 있었는지. D는 q01과 같은 정의.
set timezone='Asia/Seoul'; set statement_timeout=0;
with runs as (
  select id, target_date t, finished_at f from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS')
    and target_date >= date '2026-08-31'
), dmk(id,t,f,market_code,d) as (
  select r.id, r.t, r.f, mk, case when mk='US' then (select max(trade_date) from price_daily p join stock s on s.id=p.stock_id where s.market_code='US' and p.trade_date<=r.t and p.trade_date>=r.t-14 and p.created_at<=r.f)
     else (select max(trade_date) from price_daily p join stock s on s.id=p.stock_id where s.market_code=mk and p.trade_date<=r.t and p.trade_date>=r.t-14 and p.created_at<=r.f) end
  from runs r cross join unnest(array['KOSPI','KOSDAQ','US']) mk
)
select dmk.id, dmk.t, dmk.market_code, dmk.d, s.ticker, m.universe_code, m.effective_from, m.effective_to, m.origin,
  to_char(p.created_at,'MM-DD HH24:MI') arrived,
  (select string_agg(distinct fr.failed_phase||':'||fr.failure_kind,',') from batch_execution_failure fr where fr.execution_id=dmk.id and fr.stock_id=s.id) fail_phase
from dmk join universe_membership m on m.effective_from<=dmk.d and (m.effective_to is null or m.effective_to>dmk.d)
join universe u on u.code=m.universe_code and u.is_active
join stock s on s.id=m.stock_id and s.market_code=dmk.market_code
left join price_daily p on p.stock_id=s.id and p.trade_date=dmk.d
where p.created_at is null or p.created_at>dmk.f
order by dmk.t, dmk.market_code, s.ticker;
