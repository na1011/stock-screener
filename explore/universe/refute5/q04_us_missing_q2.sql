-- 미국 현 편입 중 최신 기말이 2026-03~04-10(달력 분기 회사의 Q1)에 멈춘 종목 — 6월 분기 10-Q(7~8월 공시)가 DB에 없다.
-- 소스(SEC)에 있는지는 묻지 않았다(외부 호출 금지). 그 분기 이전 공시 지연·마지막 행 생성 시각을 함께 본다.
SET statement_timeout='60s';
WITH cur AS (SELECT DISTINCT s.id, s.ticker FROM universe_membership m JOIN stock s ON s.id=m.stock_id
             JOIN universe u ON u.code=m.universe_code AND u.is_active WHERE m.effective_to IS NULL AND s.is_active AND s.market_code='US'),
f AS (SELECT stock_id, max(period_end) pe, max(published_at) pub, max(created_at) cr FROM financial_statement GROUP BY 1)
SELECT count(*) n, min(f.cr) min_cr, max(f.cr) max_cr,
       count(*) FILTER (WHERE f.pub < date '2026-06-01') pub_before_jun,
       string_agg(c.ticker, ' ' ORDER BY c.ticker) who
FROM cur c JOIN f ON f.stock_id=c.id WHERE f.pe BETWEEN date '2026-03-01' AND date '2026-04-10';
