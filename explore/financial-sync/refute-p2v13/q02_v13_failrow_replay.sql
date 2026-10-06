-- v13 결정 4 「(가) 무해한 선행」 재생: 정기 daily-update 실행(08-31 뒤)마다, 그 실행이 끝난 시각(finished_at) 기준으로
-- 「시장 m의 D 편입 중 절반 이상이 D 봉을 가졌는데 이 종목만 없다」인 종목 수 = (가)가 남길 PRICE 실패 행 수.
-- D = 그 실행의 target_date(시장 거래일일 때만 — indicator_trade_date). 그 실행의 지금 상태(SUCCESS/PARTIAL)와 대조:
-- 실패 행이 생기면 BatchExecutionRecorder:176이 PARTIAL_SUCCESS로 닫고 BatchExecutionContext:387이 WARNING 「daily-update 부분 실패」를 보낸다.
set timezone='Asia/Seoul';
with runs as (
  select id, target_date d, finished_at f, status, (select count(*) from batch_execution_failure x where x.execution_id=b.id) fails
  from batch_execution b
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS')
    and finished_at is not null and target_date >= date '2026-08-31'
), mem as (
  select distinct m.stock_id, s.market_code, s.ticker, m.effective_from, m.effective_to
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
), x as (
  select r.id, r.d, r.status, r.fails, mem.market_code, mem.ticker,
    exists(select 1 from price_daily p where p.stock_id=mem.stock_id and p.trade_date=r.d and p.created_at<=r.f) has_bar
  from runs r join indicator_trade_date c on c.trade_date=r.d
  join mem on mem.market_code=c.market_code and mem.effective_from<=r.d and (mem.effective_to is null or mem.effective_to>r.d)
), mk as (
  select id, market_code, count(*) n, count(*) filter (where has_bar) nb from x group by 1,2
)
select x.id, x.d, x.status, x.fails existing_fail_rows,
  count(*) filter (where not x.has_bar and mk.nb*2>=mk.n) new_fail_rows,
  string_agg(case when not x.has_bar and mk.nb*2>=mk.n then x.market_code||':'||x.ticker end, ' ' order by x.market_code, x.ticker) who,
  string_agg(distinct case when mk.nb*2<mk.n then x.market_code||'(under-half '||mk.nb||'/'||mk.n||')' end, ' ') under_half
from x join mk using (id, market_code)
group by x.id, x.d, x.status, x.fails order by x.d, x.id;
