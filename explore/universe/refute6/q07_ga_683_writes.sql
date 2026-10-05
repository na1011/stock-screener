-- (가) 80종목에 #683(09-28)이 새 행(신규·정정)을 썼는가 — 썼다면 v6 changed_at도 09-28로 찍혀 「공시일 > changed_at」 술어마저 가린다.
-- 채움·교정은 제자리 UPDATE라 여기 안 보인다(하한).
SET statement_timeout='60s';
WITH r AS (SELECT started_at, finished_at FROM batch_execution WHERE id=683),
cur AS (SELECT DISTINCT s.id, s.ticker FROM universe_membership m JOIN stock s ON s.id=m.stock_id
        JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active AND s.market_code='US'),
f AS (SELECT stock_id, max(period_end) pe FROM financial_statement GROUP BY 1),
ga AS (SELECT c.id, c.ticker FROM cur c JOIN f ON f.stock_id=c.id WHERE f.pe BETWEEN date '2026-03-01' AND date '2026-04-10')
SELECT count(DISTINCT fs.stock_id) stocks_with_new_rows, count(*) rows, min(fs.published_at), max(fs.published_at),
       string_agg(DISTINCT ga.ticker, ' ') who
FROM financial_statement fs JOIN ga ON ga.id=fs.stock_id, r WHERE fs.created_at BETWEEN r.started_at AND r.finished_at;
