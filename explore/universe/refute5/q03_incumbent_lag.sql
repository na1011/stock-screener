-- 현 편입(F6 대상 아님) 재무의 「다음 공시 예상일」 추정 — 원장(SEC/DART)에 묻지 않은 **자기 이력 모형**이다.
-- 다음 기말 = 최신 기말 + 91일 · 예상 공시일 = 다음 기말 + 그 종목 rev0 공시 지연 중앙(최근 3년).
SET statement_timeout='60s';
WITH cur AS (SELECT DISTINCT s.id, s.ticker, s.market_code FROM universe_membership m JOIN stock s ON s.id=m.stock_id
             JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active),
f AS (SELECT stock_id, max(period_end) pe, max(published_at) pub FROM financial_statement GROUP BY 1),
lag AS (SELECT stock_id, percentile_disc(0.5) WITHIN GROUP (ORDER BY published_at - period_end) lagd
        FROM financial_statement WHERE revision_no = 0 AND fiscal_period IN ('Q1','Q2','Q3','FY') AND period_end > date '2023-01-01'
          AND published_at - period_end BETWEEN 1 AND 120 GROUP BY 1),
p AS (SELECT c.*, f.pe, f.pub, l.lagd, (f.pe + 91 + l.lagd) pred FROM cur c JOIN f ON f.stock_id=c.id JOIN lag l ON l.stock_id=c.id)
SELECT market_code, count(*) n,
  count(*) FILTER (WHERE pred <= date '2026-10-04') due_by_1004,
  count(*) FILTER (WHERE pred <= date '2026-10-04' AND pred > date '2026-09-27') due_0928_1004,
  count(*) FILTER (WHERE pred BETWEEN date '2026-10-05' AND date '2026-10-29') due_1005_1029,
  count(*) FILTER (WHERE pred BETWEEN date '2026-10-30' AND date '2026-11-25') due_1030_1125,
  string_agg(ticker||':'||pe||'+'||lagd, ' ' ORDER BY pred) FILTER (WHERE pred <= date '2026-10-04') who_due
FROM p GROUP BY 1 ORDER BY 1;
