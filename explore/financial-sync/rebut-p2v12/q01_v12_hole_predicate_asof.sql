-- v12 결정 4의 「영구 구멍」 술어를 실제 정기 실행 시각(as-of)에 적용한다.
-- 각 SCHEDULER daily-update 실행 r(대상일 d · 끝 f)에서 d 편입(활성 유니버스) 종목 중 f 시점에 d 봉이 없던 것을
--   permanent_by_later = f 전에 d 뒤 봉이 이미 있었다(v12 「D 뒤 봉이 이미 있음」 → 판정 불가로 넘김)
--   permanent_by_target= f 날짜에 편입 구간 밖(「수집 대상 아님」 근사)
--   temp               = 둘 다 아님(「일시」 → 그 시장 D 미완료 → 시장 전체 판정 미룸)
-- 그리고 d 봉이 나중에 실제로 들어왔나(arrived_later) — permanent로 분류했는데 들어왔으면 술어가 틀린 것.
set timezone='Asia/Seoul';
with runs as (
  select id, target_date d, finished_at f from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS')
    and finished_at is not null and target_date >= date '2026-08-31'
), mem as (
  select m.stock_id, s.market_code, s.ticker, m.effective_from, m.effective_to
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
), miss as (
  select r.id, r.d, r.f, mem.market_code, mem.stock_id, mem.ticker, p.created_at c
  from runs r join indicator_trade_date cal on cal.trade_date=r.d
  join mem on mem.market_code=cal.market_code and mem.effective_from<=r.d and (mem.effective_to is null or mem.effective_to>r.d)
  left join price_daily p on p.stock_id=mem.stock_id and p.trade_date=r.d
  where p.created_at is null or p.created_at > r.f
), cls as (
  select miss.*,
    exists(select 1 from price_daily q where q.stock_id=miss.stock_id and q.trade_date>miss.d and q.created_at<=miss.f) later_exists,
    not exists(select 1 from mem m2 where m2.stock_id=miss.stock_id and m2.effective_from<=miss.f::date and (m2.effective_to is null or m2.effective_to>miss.f::date)) not_target
  from miss
)
select id, d, market_code, ticker,
  case when later_exists then 'permanent_by_later' when not_target then 'permanent_by_target' else 'temp' end cls,
  c is not null as arrived_later, c
from cls order by d, market_code, ticker;
