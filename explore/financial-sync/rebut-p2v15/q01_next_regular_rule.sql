-- v15 결정 1 재생: 정기 ③(daily-update SCHEDULER) 실행 끝에 「D 편입인데 D 봉 없음 ∧ 시장 절반 이상이 D 봉」인 (D, 종목)이
-- 「그다음 정기 ③ 실행 끝」까지 왔는가. 두 시장(KR = KOSPI+KOSDAQ · US). D = 실행 끝까지 그 시장 D 편입의 절반 이상이 봉을 가진 최신일(<= target_date).
-- D 편입 = effective_from <= D AND (effective_to IS NULL OR effective_to > D) · 활성 유니버스 · stock.is_active 무관(열로 표시).
set timezone='Asia/Seoul'; set statement_timeout=0;
with runs as (
  select id, target_date t, started_at s, finished_at f,
         lead(finished_at) over (order by started_at) f_next, lead(id) over (order by started_at) id_next
  from batch_execution
  where job_name='daily-update' and triggered_by='SCHEDULER' and status in ('SUCCESS','PARTIAL_SUCCESS')
    and target_date >= date '2026-08-31'
), mk as (select unnest(array['KR','US']) mk),
mem as (
  select m.stock_id, case when s.market_code='US' then 'US' else 'KR' end mk, m.effective_from, m.effective_to, s.ticker, s.is_active
  from universe_membership m join universe u on u.code=m.universe_code and u.is_active join stock s on s.id=m.stock_id
), cand as (
  select r.id, mk.mk, dd::date d,
    (select count(distinct x.stock_id) from mem x where x.mk=mk.mk and x.effective_from<=dd::date and (x.effective_to is null or x.effective_to>dd::date)) n_mem,
    (select count(distinct x.stock_id) from mem x join price_daily p on p.stock_id=x.stock_id and p.trade_date=dd::date and p.created_at<=r.f
       where x.mk=mk.mk and x.effective_from<=dd::date and (x.effective_to is null or x.effective_to>dd::date)) n_bar
  from runs r cross join mk cross join generate_series(r.t-10, r.t, interval '1 day') dd
), dpick as (
  select id, mk, max(d) d from cand where n_mem>0 and n_bar*2>=n_mem group by id, mk
), miss as (
  select r.id, r.t, dp.mk, dp.d, r.f, r.f_next, r.id_next, x.ticker, x.is_active, x.effective_to, p.created_at arrived
  from runs r join dpick dp on dp.id=r.id
  join mem x on x.mk=dp.mk and x.effective_from<=dp.d and (x.effective_to is null or x.effective_to>dp.d)
  left join price_daily p on p.stock_id=x.stock_id and p.trade_date=dp.d
  where p.created_at is null or p.created_at>r.f
)
select id, mk, d, to_char(d,'Dy') dow, to_char(f,'MM-DD HH24:MI') run_end, id_next, to_char(f_next,'MM-DD HH24:MI') next_end,
       ticker, is_active act, effective_to eff_to, to_char(arrived,'MM-DD Dy HH24:MI') arrived,
       case when arrived is null then 'NEVER'
            when f_next is null then 'NO_NEXT_YET'
            when arrived<=f_next then 'BY_NEXT_REG3'
            else 'AFTER_NEXT_REG3' end kind
from miss order by mk, d, ticker;
