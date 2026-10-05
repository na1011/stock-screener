SET statement_timeout='60s';
-- 첫 봉(상장 대용)에서 45일 안에 국내 유니버스에 든 편입 행 — 보존 창 시작(2019년 이전 상장분)은 원리적으로 안 잡힌다
WITH fb AS (SELECT stock_id, min(trade_date) AS first_bar FROM price_daily
            WHERE stock_id IN (SELECT stock_id FROM universe_membership WHERE universe_code IN ('KOSPI_TOP200','KOSDAQ_TOP150'))
            GROUP BY stock_id)
SELECT m.universe_code, s.ticker, fb.first_bar, m.effective_from, m.effective_from - fb.first_bar AS days, m.effective_to
FROM universe_membership m JOIN stock s ON s.id = m.stock_id JOIN fb ON fb.stock_id = m.stock_id
WHERE m.universe_code IN ('KOSPI_TOP200','KOSDAQ_TOP150')
  AND m.effective_from - fb.first_bar BETWEEN 0 AND 45 AND fb.first_bar > date '2020-01-31'
ORDER BY m.effective_from;
