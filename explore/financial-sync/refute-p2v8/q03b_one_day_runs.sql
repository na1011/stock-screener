-- 알림 항목 1(005930 · 「저평가 가치주」 조건 8)을 보존 창 날짜마다 지금 값으로 다시 판정 → 참 구간(런)의 길이 분포.
-- 보류(결정 6)가 그 날 판정을 건너뛰고 같은 asOf를 다시 판정하지 않으면, 보류일에만 참이던 런은 알림이 영영 안 나간다.
-- 같은 화면을 KOSPI 현 편입 전체에 적용한 런 길이도 함께(기제의 빈도 — 알림 항목은 005930 하나뿐).
with v as (
  select v.stock_id, v.trade_date,
    max(value) filter (where indicator_code='PER_PCTL') per_p,
    max(value) filter (where indicator_code='PBR_PCTL') pbr_p,
    max(value) filter (where indicator_code='GP_TO_ASSETS_PCTL') gpa_p,
    max(value) filter (where indicator_code='ACCRUAL_RATIO') acc,
    max(value) filter (where indicator_code='ROA') roa,
    max(value) filter (where indicator_code='SHARES_GROWTH_3Y') sg,
    max(value) filter (where indicator_code='DEBT_RATIO') debt,
    max(value) filter (where indicator_code='ADJ_CLOSE') px,
    max(value) filter (where indicator_code='SMA_200') sma
  from indicator_value v join stock s on s.id=v.stock_id
  where s.market_code='KOSPI' and v.trade_date >= '2025-09-15'
    and indicator_code in ('PER_PCTL','PBR_PCTL','GP_TO_ASSETS_PCTL','ACCRUAL_RATIO','ROA','SHARES_GROWTH_3Y','DEBT_RATIO','ADJ_CLOSE','SMA_200')
  group by 1,2
), sat as (
  select stock_id, trade_date,
    coalesce(per_p<=0.3 and pbr_p<=0.3 and gpa_p>=0.5 and acc<=0 and roa>=0 and sg<=0 and debt<=150 and px>=sma, false) ok
  from v
), runs as (
  select stock_id, trade_date, ok,
    row_number() over (partition by stock_id order by trade_date)
    - row_number() over (partition by stock_id, ok order by trade_date) grp
  from sat
), r as (
  select stock_id, ok, grp, count(*) len, min(trade_date) d0, max(trade_date) d1 from runs group by 1,2,3
)
select s.ticker, d0, d1, len from r join stock s on s.id=r.stock_id where ok and len=1 order by d0 limit 20;
