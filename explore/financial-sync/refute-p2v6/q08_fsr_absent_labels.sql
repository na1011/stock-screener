-- FSR이 그 라벨에 아무 칸도 안 쓴 꼴(FSR 전용 다섯 칸이 모두 비었다) — v6 결정 3a의 (a) 「FSR 번호 < R 또는 013」이 앞으로 표지를 달 꼴.
-- 기존 행은 표지가 없으니 지배주주 폴백 계산기가 연결 기저로 값을 낸다. 그 종목의 다른 라벨엔 FSR 칸이 있다(종목 전체 OFS·무FSR은 뺌).
with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code
             where m.effective_to is null and u.market_code in ('KOSPI','KOSDAQ')),
l as (select fs.*, (fs.total_equity_controlling is null and fs.net_income_controlling is null and fs.cost_of_sales is null
                    and fs.cash_and_equivalents is null and fs.inventories is null) as fsr_absent
      from financial_statement fs where fs.is_latest and fs.stock_id in (select stock_id from cur) and fs.fiscal_year>=2018 and fs.fiscal_period<>'Q4'),
o as (select l.*, bool_or(not fsr_absent) over (partition by stock_id) as stock_has_fsr,
             bool_or(total_equity_controlling is not null) over (partition by stock_id) as stock_has_teqc from l)
select fiscal_year, count(*) filter (where fsr_absent and stock_has_fsr) absent_rows,
       count(distinct stock_id) filter (where fsr_absent and stock_has_fsr) absent_stocks,
       count(*) filter (where fsr_absent and stock_has_teqc) absent_rows_teqc_stock,
       count(*) filter (where fsr_absent and stock_has_teqc and total_equity is not null and total_equity_controlling is null) absent_with_total_equity
from o group by 1 order by 1;
