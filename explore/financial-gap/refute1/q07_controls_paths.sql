-- RG0 대조군(AAPL·MSFT·JPM)의 최신 정기공시가 옛 라벨에 판을 만들었나(비교열 판 경로를 RG0가 태우는가)
select s.ticker, latest.report_code, latest.published_at,
  (select count(*) from financial_statement o where o.stock_id=s.id and o.report_code=latest.report_code and o.period_end < latest.period_end) old_label_rows
from stock s cross join lateral (select report_code, published_at, period_end from financial_statement f where f.stock_id=s.id and f.report_code ~ '^10-[KQ]:' order by published_at desc, period_end desc limit 1) latest
where s.market_code='US' and s.ticker in ('AAPL','MSFT','JPM') order by 1;
