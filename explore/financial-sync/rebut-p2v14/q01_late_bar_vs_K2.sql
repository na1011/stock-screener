-- 결정 4 K=2(13:30·15:00) 대상: 정기 daily-update 실행(③)의 「실행 시작 시각에 열린 편입(지금 ③ 모집단)」 중
-- 실행 끝에 D 봉이 없던 (D, 종목)과 그 봉이 언제 왔는지. D = 그 실행 끝까지 적재된 시장 최신일(q01 v13과 같은 정의).
-- 「같은 날 15:00·17:00에 왔을 수 있나」의 증거: 같은 KST 날짜의 다른 실행(손·관리자 포함)이 그 봉을 받았는가.
set timezone='Asia/Seoul'; set statement_timeout=0;
with runs as (
  select id, target_date t, started_at s, finished_at f from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS')
    and target_date >= date '2026-08-31'
), d as (
  select r.*, (select max(p.trade_date) from price_daily p join stock s on s.id=p.stock_id
               where s.market_code='US' and p.trade_date<=r.t and p.trade_date>=r.t-14 and p.created_at<=r.f) d
  from runs r
), miss as (
  select d.id, d.t, d.d, d.f, s.ticker, s.id sid, p.created_at arrived
  from d join universe_membership m on m.effective_from<=d.d and (m.effective_to is null or m.effective_to > d.s::date)
  join universe u on u.code=m.universe_code and u.is_active
  join stock s on s.id=m.stock_id and s.market_code='US'
  left join price_daily p on p.stock_id=s.id and p.trade_date=d.d
  where (p.created_at is null or p.created_at>d.f)
)
select id, d, to_char(f,'MM-DD HH24:MI') run_end, ticker,
       to_char(arrived,'MM-DD Dy HH24:MI') arrived,
       round(extract(epoch from (arrived - f))/3600.0,1) h_after_run,
       case when arrived is null then 'NEVER'
            when arrived::date = f::date then 'SAME_DAY'
            else 'LATER' end kind,
       case when ticker ~ '\.' or ticker ~ 'U$' and length(ticker)=5 then 'CIKREP_CAND' else 'plain' end cls
from miss order by d, ticker;
