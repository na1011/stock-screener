-- 주식수 기저 표지가 붙은 종목 전부와 현 편입 여부(시세 판정 유니버스 셋). 새 판(단계·무거운 잡이 쓴 행)은 14:18 유도 전까지 NULL = OK(FinancialFigures:99-109).
select s.ticker, f.share_basis_status, count(*) rows_flagged, min(f.published_at), max(f.published_at),
       bool_or(um.stock_id is not null) current_member
from financial_statement f join stock s on s.id=f.stock_id
left join universe_membership um on um.stock_id=f.stock_id and um.effective_to is null and um.universe_code in ('US_TOP1000','KOSPI_TOP200','KOSDAQ_TOP150')
where f.share_basis_status is not null group by 1,2 order by 1;
