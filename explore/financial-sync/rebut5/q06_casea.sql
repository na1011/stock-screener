-- 0콜 · 운영 SELECT: 보충 대상 accn(83 CIK 캐시 · q06_targets.tsv)의 filed 날짜에 그 종목 행이 이미 있나 — 「회수 건너뜀」이 지킬 행이 있는 경우(a)의 수
\pset pager off
CREATE TEMP TABLE t(ticker text, accn text, form text, filed date);
\copy t FROM 'q06_targets.tsv'
SELECT t.form, count(*) targets, count(s.id) stock_found,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=s.id AND f.published_at=t.filed)) rows_on_filed
FROM t LEFT JOIN stock s ON s.ticker=t.ticker AND s.market_code='US' GROUP BY 1 ORDER BY 1;
SELECT t.ticker, t.accn, t.form, t.filed, (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id AND f.published_at=t.filed) n
FROM t JOIN stock s ON s.ticker=t.ticker AND s.market_code='US'
WHERE EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=s.id AND f.published_at=t.filed);
