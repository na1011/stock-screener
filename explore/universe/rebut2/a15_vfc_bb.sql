-- VFC·BB 최신 기말 — 09-28 전량 FR(#683) 대상이었던 VFC가 아직 03-28에 멈춰 있으면 F6(편입 미도달)가 아니라 다른 결함이다.
SELECT s.ticker, f.fiscal_year, f.fiscal_period, f.period_end, f.published_at, f.revision_no, f.is_latest, f.created_at::timestamp(0)
FROM stock s JOIN financial_statement f ON f.stock_id=s.id
WHERE s.market_code='US' AND s.ticker IN ('VFC','BB') AND f.period_end >= '2025-12-01' ORDER BY 1, f.period_end, f.published_at;
