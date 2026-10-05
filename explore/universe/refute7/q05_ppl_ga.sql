-- PPL(A가 토요일에 PPLC 대신 들이는 대표)의 6월 분기 부재가 companyfacts 결손인지 0콜로: 같은 CIK의 PPLC는 #683(09-28 FR)을 받았다.
SET statement_timeout='60s';
SELECT s.ticker, s.id, max(f.period_end) pe, max(f.published_at) pub, min(f.created_at)::date first_created, max(f.created_at)::date last_created, count(*) n
FROM stock s JOIN financial_statement f ON f.stock_id=s.id WHERE s.ticker IN ('PPL','PPLC') AND s.market_code='US' GROUP BY 1,2;
-- PPLC는 #683 시각에 현 편입이었나
SELECT s.ticker, m.universe_code, m.effective_from, m.effective_to, m.created_at::date
FROM universe_membership m JOIN stock s ON s.id=m.stock_id WHERE s.ticker IN ('PPL','PPLC') AND m.universe_code='US_TOP1000' ORDER BY 1,3;
