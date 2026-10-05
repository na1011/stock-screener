-- 옛 대표 칸: 옛 대표가 편입이던 (종목, 날짜)의 indicator_value 행 수 — 평면별 · 단위/클래스별
with wrong as (select id, ticker from stock where market_code='US' and ticker in ('PPLC','BTSGU','TAP.A','MOG.B','WSO.B','MKC.V','BF.A','RUSHB','ZG','LBTYB','BIO.B','GOOG')),
mem as (select w.id, w.ticker, m.origin, m.effective_from, coalesce(m.effective_to, '2099-01-01') effective_to from universe_membership m join wrong w on w.id=m.stock_id where m.universe_code='US_TOP1000')
select case when ticker in ('PPLC','BTSGU') then 'unit' else 'class' end grp, origin, ticker, count(*) cells
from mem join indicator_value v on v.stock_id=mem.id and v.trade_date >= mem.effective_from and v.trade_date < mem.effective_to
group by rollup(1,2,3) order by 1,2,3;
-- 창 안 거래량 0 봉
select s.ticker, count(*) filter (where p.trade_date >= '2025-09-29' and p.volume = 0) win_zero_vol
from price_daily p join stock s on s.id=p.stock_id
where s.market_code='US' and s.ticker in ('GOOG','ZG','RUSHB','BF.A','MKC.V','TAP.A','MOG.B','WSO.B','BIO.B','LBTYB','PPLC','BTSGU') group by 1 order by 1;
-- 같은 성질(관측 평면에 매주 빠짐): 대형주 편입 0행
select s.ticker, (select count(*) from universe_membership m where m.stock_id=s.id and m.universe_code='US_TOP1000') memb,
 (select count(*) from financial_statement f where f.stock_id=s.id) fin,
 (select count(*) from financial_statement f where f.stock_id=s.id and f.shares_outstanding>0 and f.period_end>='2024-10-02') sh_rows
from stock s where s.market_code='US' and s.ticker in ('V','BRK.A','BRK.B','XOM','AMZN','JNJ') order by 1;
-- 형제 시세 마지막 봉
select s.ticker, max(p.trade_date) last_bar, count(*) bars from stock s left join price_daily p on p.stock_id=s.id
where s.market_code='US' and s.ticker in ('TAP','PPL','Z','RUSHA','BF.B','GOOG','MKC','WSO','MOG.A','BTSG','NWSA','UHAL.B','LBTYA','LBTYK','LLYVA','HEI.A','LEN.B','FOX') group by 1 order by 1;
-- 관측 편입 날짜(재생 대조 날짜)
select effective_from, count(*) from universe_membership where universe_code='US_TOP1000' and origin='OBSERVED' group by 1 order by 1;
-- 미국 워치리스트
select count(*) from watchlist_item w join stock s on s.id=w.stock_id where s.market_code='US';
