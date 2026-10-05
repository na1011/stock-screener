-- 0콜 · 운영 SELECT: (1) ADR-0135 펜스 상한 적용 전/후 (2) G 시계 — #626·#632 기준 25일 경계
WITH r AS (SELECT universe_code uc, min(created_at) restored_at, max(effective_to) cap FROM universe_membership WHERE origin='RESTORED' GROUP BY 1)
SELECT r.uc, s.ticker, count(*) n, sum((f.published_at<=r.cap)::int) le_cap, min(f.published_at), max(f.published_at)
FROM r JOIN universe u ON u.code=r.uc JOIN stock s ON s.market_code=u.market_code JOIN financial_statement f ON f.stock_id=s.id
WHERE f.created_at > r.restored_at AND f.shares_outstanding IS NOT NULL GROUP BY 1,2;
SELECT id, started_at, finished_at-started_at dur,
  timestamptz '2026-10-15 14:15+09' - started_at d1015, timestamptz '2026-10-16 14:15+09' - started_at d1016,
  timestamptz '2026-10-19 14:15+09' - started_at d1019
FROM batch_execution WHERE id IN (626,632);
