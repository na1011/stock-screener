SET statement_timeout='60s';
-- 미국 is_active 종목 중 재무 행은 있는데 최근 2년 주식수가 0행인 것
select s.ticker, s.name, s.sic_code, count(*) fs_rows,
       max(f.period_end) last_pe
from stock s join financial_statement f on f.stock_id=s.id
where s.market_code='US' and s.is_active
group by s.id, s.ticker, s.name, s.sic_code
having count(*) filter (where f.shares_outstanding > 0 and f.period_end >= date '2024-10-05') = 0
order by s.ticker;
