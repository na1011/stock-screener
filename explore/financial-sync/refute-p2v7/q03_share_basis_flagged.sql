-- 주식수 기저 판정(14:18 유도)이 OK가 아닌 종목과 편입 — 12:30 단계가 쓴 새 판은 유도 전까지 NULL(= OK로 읽힘)
select s.ticker, f.share_basis_status, um.universe_code, um.effective_from, um.effective_to
from (select distinct stock_id, share_basis_status from financial_statement where is_latest and share_basis_status is not null) f
join stock s on s.id=f.stock_id left join universe_membership um on um.stock_id=f.stock_id order by 1, um.effective_from;
