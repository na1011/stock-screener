-- 결정 5(이른 판 끔): 09-18(#601 · 이른판=2257) 뒤 US_TOP1000에 처음 든 현 편입 vs #601이 덮은 현 편입 — 이른 판 행(#601 창에 생긴 행) 분포.
SET statement_timeout='60s';
WITH r AS (SELECT started_at, finished_at FROM batch_execution WHERE id=601),
cur AS (SELECT s.id, s.ticker, min(m.effective_from) first_from, bool_or(m.created_at <= (SELECT started_at FROM r) AND m.effective_from <= date '2026-09-18' AND (m.effective_to IS NULL OR m.effective_to > date '2026-09-18')) covered601
        FROM universe_membership m JOIN stock s ON s.id=m.stock_id WHERE m.universe_code='US_TOP1000' AND s.is_active
        AND s.id IN (SELECT stock_id FROM universe_membership WHERE universe_code='US_TOP1000' AND effective_to IS NULL) GROUP BY 1,2),
ev AS (SELECT f.stock_id, count(*) n FROM financial_statement f, r WHERE f.created_at BETWEEN r.started_at AND r.finished_at GROUP BY 1),
nl AS (SELECT stock_id, count(*) FILTER (WHERE NOT is_latest) nonlatest FROM financial_statement GROUP BY 1)
SELECT covered601, count(*) stocks, count(ev.n) with_early, coalesce(sum(ev.n),0) early_rows,
       round(avg(coalesce(nl.nonlatest,0)),2) avg_nonlatest,
       string_agg(ticker || ':' || coalesce(nl.nonlatest,0), ' ' ORDER BY ticker) FILTER (WHERE NOT covered601) who
FROM cur LEFT JOIN ev ON ev.stock_id=cur.id LEFT JOIN nl ON nl.stock_id=cur.id GROUP BY 1;
