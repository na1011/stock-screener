-- B-4 이른 판: 이른 판 삽입이 켜진 실행(#601·#602 09-18 · #710 10-03 손)이 넣은 행이 있는 종목 — 미국 현 편입을 편입 시작으로 가른다
-- 이른 판 행 근사: is_latest=false ∧ 같은 라벨의 최신본보다 공시일이 이른 행 ∧ created_at이 이른판 실행 창 안
WITH ev_runs AS (SELECT id, started_at, finished_at FROM batch_execution WHERE id IN (601,602,710)),
ev AS (SELECT DISTINCT f.stock_id FROM financial_statement f JOIN ev_runs r ON f.created_at BETWEEN r.started_at AND r.finished_at
       WHERE NOT f.is_latest),
cur AS (SELECT m.stock_id, m.effective_from FROM universe_membership m WHERE m.universe_code='US_TOP1000' AND m.effective_to IS NULL),
-- 그 실행들이 대상으로 삼은 집합(09-18 그 시각 편입 1000 + 208 과거 편입)은 재구성 못 함 — 편입 시작으로만 가른다
x AS (SELECT c.*, (c.stock_id IN (SELECT stock_id FROM ev)) has_ev,
        (SELECT count(*) FROM financial_statement f WHERE f.stock_id=c.stock_id AND NOT f.is_latest) nonlatest
      FROM cur c)
SELECT CASE WHEN effective_from <= '2026-09-18' THEN 'a_start<=09-18' ELSE 'b_start>09-18' END grp,
       count(*) members, count(*) FILTER (WHERE has_ev) with_early_vintage_rows,
       round(avg(nonlatest),1) avg_nonlatest_rows
FROM x GROUP BY 1 ORDER BY 1;
-- 09-18 뒤 편입의 개별
WITH ev_runs AS (SELECT id, started_at, finished_at FROM batch_execution WHERE id IN (601,602,710)),
ev AS (SELECT f.stock_id, count(*) n FROM financial_statement f JOIN ev_runs r ON f.created_at BETWEEN r.started_at AND r.finished_at WHERE NOT f.is_latest GROUP BY 1)
SELECT s.ticker, m.effective_from, coalesce(ev.n,0) early_rows
FROM universe_membership m JOIN stock s ON s.id=m.stock_id LEFT JOIN ev ON ev.stock_id=m.stock_id
WHERE m.universe_code='US_TOP1000' AND m.effective_to IS NULL AND m.effective_from > '2026-09-18' ORDER BY 2,1;
