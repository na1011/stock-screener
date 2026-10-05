-- 계획 v2 실측 「편입 뒤 재무를 한 번도 새로 안 받았다」의 측정 수단 점검: created_at은 제자리 교정(UPDATE)을 못 본다(FinancialWriter:644).
-- 그 다섯이 09-28 01:14 전량 FR(#683, 대상 1000 = 그때 현재 편입) 시점에 편입 중이었나.
SELECT s.ticker,
  (SELECT string_agg(m.origin||':'||m.effective_from||'~'||coalesce(m.effective_to::text,''),' ' ORDER BY m.effective_from) FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000') top,
  (SELECT max(created_at)::timestamp(0) FROM financial_statement f WHERE f.stock_id=s.id) fin_last_created,
  EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000' AND m.effective_from <= '2026-09-28' AND (m.effective_to IS NULL OR m.effective_to > '2026-09-28') AND m.origin='OBSERVED') member_at_683
FROM stock s WHERE s.market_code='US' AND s.ticker IN ('TEM','BLLN','BETA','GRAL','KOD','VFC','BB') ORDER BY 1;
