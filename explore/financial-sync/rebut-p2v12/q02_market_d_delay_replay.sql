-- v12 결정 4 재생: 시장 D가 「완료」가 되는 첫 정기 실행(as-of created_at)과 그 지연(실행 수).
-- 완료 = D 편입(활성 유니버스 · 지금 표의 구간) 전원이 f 전에 D 봉을 가졌거나, 영구 구멍
--   (f 전에 D 뒤 봉이 있음, 또는 f 날짜에 그 종목이 어느 활성 유니버스에도 편입이 아님 — 「수집 대상 아님」의 관대한 근사).
-- 「수집 대상」을 수집기 모집단(보존 창 과거 편입 · priceGapRuns)으로 읽으면 지연은 이보다 길다(CRNX·APGE는 영영).
-- (첫 판은 bool_and가 NULL을 건너뛰어 봉 없는 종목을 완료로 셌다 — coalesce로 고침)
-- 지연 = D의 자기 실행부터 첫 완료 실행까지 실행 수(0 = 제때). 접두 규칙(앞 D가 미완이면 뒤 D도 판정 안 함)은 prefix_delay.
set timezone='Asia/Seoul';
with runs as (
  select id, target_date d, finished_at f, row_number() over (order by finished_at) rn from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS')
    and finished_at is not null and target_date >= date '2026-08-31'
), mem as (
  select m.stock_id, s.market_code, m.effective_from, m.effective_to
  from universe_membership m join stock s on s.id=m.stock_id join universe u on u.code=m.universe_code and u.is_active
), dd as (
  select distinct cal.market_code, r.d, r.rn own_rn from runs r join indicator_trade_date cal on cal.trade_date=r.d
), chk as (
  select dd.market_code, dd.d, dd.own_rn, r.rn, r.f,
    bool_and( coalesce(p.created_at <= r.f, false)
      or exists(select 1 from price_daily q where q.stock_id=mem.stock_id and q.trade_date>dd.d and q.created_at<=r.f)
      or not exists(select 1 from mem m2 where m2.stock_id=mem.stock_id and m2.effective_from<=r.f::date and (m2.effective_to is null or m2.effective_to>r.f::date))
    ) complete
  from dd join runs r on r.rn >= dd.own_rn
  join mem on mem.market_code=dd.market_code and mem.effective_from<=dd.d and (mem.effective_to is null or mem.effective_to>dd.d)
  left join price_daily p on p.stock_id=mem.stock_id and p.trade_date=dd.d
  group by 1,2,3,4,5
), first_ok as (
  select market_code, d, own_rn, min(rn) filter (where complete) ok_rn from chk group by 1,2,3
)
select market_code, d, coalesce((ok_rn-own_rn)::text,'never') delay_runs,
  coalesce((max(ok_rn) over (partition by market_code order by d) - own_rn)::text,'never') prefix_delay
from first_ok order by 1,2;
