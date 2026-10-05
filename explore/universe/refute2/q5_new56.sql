-- 08-31 뒤 관측으로 든 미국 종목(지금 편입 여부 무관): 편입 구간 · 재무 created_at 최대 · 09-28 실행 683(현 편입 1000) 시점 편입이었나
WITH adds AS (
  SELECT DISTINCT m.stock_id FROM universe_membership m
  WHERE m.universe_code='US_TOP1000' AND m.origin='OBSERVED' AND m.effective_from > '2026-08-31')
SELECT s.ticker,
  (SELECT string_agg('['||m.effective_from||','||coalesce(m.effective_to::text,'')||')',' ' ORDER BY m.effective_from) FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000' AND m.origin='OBSERVED') AS obs,
  (SELECT max(created_at)::date FROM financial_statement f WHERE f.stock_id=s.id) AS fin_max_created,
  EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000'
          AND m.effective_from <= '2026-09-28' AND (m.effective_to IS NULL OR m.effective_to > '2026-09-28')) AS member_on_0928,
  EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000' AND m.effective_to IS NULL) AS current
FROM stock s JOIN adds a ON a.stock_id=s.id
ORDER BY member_on_0928, fin_max_created, s.ticker;
