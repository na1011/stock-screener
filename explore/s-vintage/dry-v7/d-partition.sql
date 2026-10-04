\set ON_ERROR_STOP 1
-- 판정 D 칸: 창 편입(windowMemberIds 술어) 종목을 마지막 작성자로 가른다.
CREATE TEMP TABLE w AS
SELECT DISTINCT m.stock_id FROM universe_membership m
JOIN stock s ON s.id = m.stock_id
JOIN universe u ON u.code = m.universe_code AND u.is_active
WHERE s.is_active AND s.market_code IN ('KOSPI','KOSDAQ','US')
  AND (m.effective_to IS NULL
       OR (m.effective_from <= (SELECT max(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)
           AND m.effective_to > (SELECT min(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)));
CREATE TEMP TABLE cur AS
SELECT DISTINCT m.stock_id, min(m.created_at) OVER (PARTITION BY m.stock_id) first_created
FROM universe_membership m JOIN universe u ON u.code = m.universe_code AND u.is_active WHERE m.effective_to IS NULL;
-- (ii) 마지막 13:30(10-02 13:30 · 대상 10-01) 뒤에 생긴 열린 편입
CREATE TEMP TABLE entrants AS
SELECT DISTINCT m.stock_id FROM universe_membership m JOIN universe u ON u.code = m.universe_code AND u.is_active
WHERE m.effective_to IS NULL AND m.created_at > '2026-10-02 13:30+09';
SELECT 'ii', s.ticker, s.market_code, (SELECT count(*) FROM indicator_value iv WHERE iv.stock_id = s.id) iv_rows
FROM entrants e JOIN stock s ON s.id = e.stock_id ORDER BY 3,2;
SELECT 'i', count(*) FROM w WHERE stock_id IN (SELECT stock_id FROM cur) AND stock_id NOT IN (SELECT stock_id FROM entrants);
SELECT 'iii', CASE WHEN lastto <= '2026-09-23' THEN 'a<=09-23' WHEN lastto <= '2026-09-28' THEN 'b09-24..28' ELSE 'c>09-28' END, count(*)
FROM (SELECT w.stock_id, (SELECT max(effective_to) FROM universe_membership m WHERE m.stock_id = w.stock_id) lastto
      FROM w WHERE stock_id NOT IN (SELECT stock_id FROM cur)) x GROUP BY 2 ORDER BY 2;
\copy (SELECT x.stock_id, CASE WHEN x.stock_id IN (SELECT stock_id FROM entrants) THEN 'ii' WHEN x.stock_id IN (SELECT stock_id FROM cur) THEN 'i' WHEN lastto <= '2026-09-23' THEN 'iii-a' WHEN lastto <= '2026-09-28' THEN 'iii-b' ELSE 'iii-c' END FROM (SELECT w.stock_id, (SELECT max(effective_to) FROM universe_membership m WHERE m.stock_id = w.stock_id) lastto FROM w) x ORDER BY 1) TO '/private/tmp/claude-501/-Users-na1011-dev-stock-screener/542f2076-f436-4591-9e74-12b004e1f464/scratchpad/dry/d-partition.tsv'
