-- 결정 3a(표지 → null)가 A48 ①(비지배 0이라 폴백이 정확한 값)과 겹치는 크기: 국내 현 편입 중 최근 3년 지배주주 자본이 있는 모든 라벨에서 지배 = 총(비지배 0)인 종목.
with cur as (select distinct m.stock_id from universe_membership m join universe u on u.code=m.universe_code
             where m.effective_to is null and u.market_code in ('KOSPI','KOSDAQ')),
l as (select fs.stock_id, fs.total_equity te, fs.total_equity_controlling teqc, fs.net_income ni, fs.net_income_controlling nic
      from financial_statement fs where fs.is_latest and fs.stock_id in (select stock_id from cur) and fs.period_end >= '2023-07-01')
select count(*) stocks,
       count(*) filter (where n_teqc>0 and n_nci=0) nci_zero_all_labels,
       count(*) filter (where n_nci>0) nci_present,
       count(*) filter (where n_teqc=0) no_teqc
from (select stock_id, count(teqc) n_teqc, count(*) filter (where teqc is not null and teqc <> te) n_nci from l group by 1) z;
