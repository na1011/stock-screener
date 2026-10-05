-- 국내 현 편입: 가장 최근 기간(is_latest)의 OCF가 NULL인 종목 — FS(전체본)가 아직 안 채운 새 기간
WITH cur AS (SELECT DISTINCT m.stock_id FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
             JOIN stock s ON s.id=m.stock_id WHERE m.effective_to IS NULL AND s.market_code IN ('KOSPI','KOSDAQ')),
lastp AS (SELECT DISTINCT ON (f.stock_id) f.stock_id, f.fiscal_year, f.fiscal_period, f.period_end, f.published_at, f.operating_cash_flow, f.created_at
          FROM financial_statement f JOIN cur c ON c.stock_id=f.stock_id WHERE f.is_latest ORDER BY f.stock_id, f.period_end DESC, f.fiscal_period)
SELECT count(*) members, count(*) FILTER (WHERE operating_cash_flow IS NULL) ocf_null_latest,
       string_agg(s.ticker||':'||fiscal_year||fiscal_period, ' ') FILTER (WHERE operating_cash_flow IS NULL) who
FROM lastp JOIN stock s ON s.id=lastp.stock_id;
