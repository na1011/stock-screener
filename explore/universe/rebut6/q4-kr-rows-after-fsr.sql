SET statement_timeout='60s';
-- 국내 현 편입: 마지막 덮은 FSR 실행(#663 KOSPI 09-23 00:09 · #648 KOSDAQ 09-21 23:19) 뒤에 「만들어진」 is_latest 행을 가진 종목
-- = 행 생성 시각(created_at) 술어가 잡는 FSR 대상. 계획의 changed_at(FR 실행만 기록 · 초기 NULL)은 이것을 못 본다.
WITH cur AS (
  SELECT s.id, s.ticker, m.universe_code, max(m.effective_from) start
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active AND s.market_code IN ('KOSPI','KOSDAQ') GROUP BY 1,2,3),
fsr AS (SELECT * FROM (VALUES ('KOSPI_TOP200', timestamptz '2026-09-23 00:09:45+09'), ('KOSDAQ_TOP150', timestamptz '2026-09-21 23:19:06+09')) v(u, t))
SELECT c.universe_code, c.ticker, c.start,
       count(*) FILTER (WHERE f.created_at > fsr.t) rows_after,
       min(f.created_at) FILTER (WHERE f.created_at > fsr.t) first_after,
       count(*) FILTER (WHERE f.created_at > fsr.t AND f.total_equity_controlling IS NULL AND f.fiscal_period IN ('FY','Q1','Q2','Q3','Q4')) after_without_ctrl_equity
FROM cur c JOIN fsr ON fsr.u = c.universe_code
JOIN financial_statement f ON f.stock_id = c.id AND f.is_latest
GROUP BY 1,2,3 HAVING count(*) FILTER (WHERE f.created_at > fsr.t) > 0 ORDER BY 1, 3, 2;
