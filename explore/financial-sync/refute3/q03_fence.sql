-- 0콜 · ADR-0135 「복원이 재무보다 먼저」 펜스: 지금 값 · published_at <= 복원 상한을 건 값 · #683 뒤 생성 행
WITH r AS (SELECT universe_code uc, min(created_at) restored_at, max(effective_to) cap FROM universe_membership WHERE origin='RESTORED' GROUP BY 1)
SELECT r.uc, r.restored_at::date, r.cap,
 (SELECT count(*) FROM financial_statement f JOIN stock s ON s.id=f.stock_id JOIN universe u ON u.code=r.uc WHERE s.market_code=u.market_code AND f.created_at > r.restored_at AND f.shares_outstanding IS NOT NULL) fin,
 (SELECT count(*) FROM financial_statement f JOIN stock s ON s.id=f.stock_id JOIN universe u ON u.code=r.uc WHERE s.market_code=u.market_code AND f.created_at > r.restored_at AND f.shares_outstanding IS NOT NULL AND f.published_at <= r.cap) fin_capped
FROM r;
