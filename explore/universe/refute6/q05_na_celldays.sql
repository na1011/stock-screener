-- (나) 크기 모형: refute5/q03의 자기 이력 모형(예상 공시일 = 최신 기말 + 91 + 그 종목 rev0 지연 중앙) — 원장에 안 물음.
-- 정기 FR = 10-30(첫 연체 · reload-gate.md [V] 행) 다음 = 11-26(25일 최소 간격 뒤 첫 평일 근사). 공시 → 다음 FR까지 평일 수 × 그 종목 최신일 FUNDAMENTAL 칸 수.
-- B 비교: 첫 평일 대상 21이 B 없으면 10-30 FR까지(10-12 → 10-30 평일 14) 기다리는 칸-일(상한 = 전 FUNDAMENTAL 칸).
SET statement_timeout='60s';
WITH cur AS (SELECT DISTINCT s.id, s.ticker, s.market_code FROM universe_membership m JOIN stock s ON s.id=m.stock_id
             JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active),
f AS (SELECT stock_id, max(period_end) pe FROM financial_statement GROUP BY 1),
lag AS (SELECT stock_id, percentile_disc(0.5) WITHIN GROUP (ORDER BY published_at - period_end) lagd
        FROM financial_statement WHERE revision_no = 0 AND fiscal_period IN ('Q1','Q2','Q3','FY') AND period_end > date '2023-01-01'
          AND published_at - period_end BETWEEN 1 AND 120 GROUP BY 1),
p AS (SELECT c.*, (f.pe + 91 + l.lagd) pred FROM cur c JOIN f ON f.stock_id=c.id JOIN lag l ON l.stock_id=c.id),
lt AS (SELECT market_code, max(trade_date) d FROM indicator_trade_date GROUP BY 1),
cells AS (SELECT v.stock_id, count(*) n FROM indicator_value v JOIN indicator i ON i.code=v.indicator_code AND i.category='FUNDAMENTAL'
          JOIN stock s ON s.id=v.stock_id JOIN lt ON lt.market_code=s.market_code AND lt.d=v.trade_date GROUP BY 1),
w AS (SELECT p.*, coalesce(cells.n,0) n,
        CASE WHEN pred BETWEEN date '2026-10-05' AND date '2026-10-29' THEN date '2026-10-30'
             WHEN pred BETWEEN date '2026-10-30' AND date '2026-11-25' THEN date '2026-11-26' END next_fr
      FROM p LEFT JOIN cells ON cells.stock_id=p.id)
SELECT market_code, count(*) FILTER (WHERE next_fr IS NOT NULL) stocks,
       sum(n * (SELECT count(*) FROM generate_series(pred, next_fr - 1, interval '1 day') g WHERE extract(isodow FROM g) < 6)) FILTER (WHERE next_fr IS NOT NULL) cell_days,
       round(avg((SELECT count(*) FROM generate_series(pred, next_fr - 1, interval '1 day') g WHERE extract(isodow FROM g) < 6)) FILTER (WHERE next_fr IS NOT NULL),1) avg_wkdays
FROM w GROUP BY 1 ORDER BY 1;
