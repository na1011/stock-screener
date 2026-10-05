-- B-5(국내 포함): 첫 평일 국내 대상 8의 재무 상태 — 최신 기간 · 최신본 중 OCF NULL · 지배주주 자본 NULL · 마지막 행 생성
WITH t(ticker) AS (VALUES ('077360'),('126340'),('253590'),('092870'),('456160'),('490470'),('007810'),('001740'))
SELECT s.ticker, s.market_code,
  count(*) FILTER (WHERE f.is_latest) latest_rows,
  max(f.fiscal_year*10 + CASE f.fiscal_period WHEN 'Q1' THEN 1 WHEN 'Q2' THEN 2 WHEN 'Q3' THEN 3 ELSE 4 END) FILTER (WHERE f.is_latest) max_period,
  count(*) FILTER (WHERE f.is_latest AND f.fiscal_year>=2024 AND f.fiscal_period<>'Q4' AND f.operating_cash_flow IS NULL) ocf_null_fy24p,
  count(*) FILTER (WHERE f.is_latest AND f.fiscal_year>=2024 AND f.fiscal_period<>'Q4') rows_fy24p,
  max(f.published_at) max_pub, max(f.created_at)::timestamp(0) max_created,
  (SELECT string_agg(universe_code||':'||origin||'['||effective_from||','||coalesce(effective_to::text,'')||')',' ' ORDER BY effective_from)
     FROM universe_membership m WHERE m.stock_id=s.id) rows_
FROM t JOIN stock s ON s.ticker=t.ticker LEFT JOIN financial_statement f ON f.stock_id=s.id
GROUP BY s.id, s.ticker, s.market_code ORDER BY 1;
