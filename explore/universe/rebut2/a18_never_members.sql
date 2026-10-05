-- 08-31 뒤 OBSERVED 편입 중 그 유니버스에 앞선 편입 행(RESTORED 포함 = 보존 창 이력)이 없는 것 — 09-22 과거 편입 FR(8년)·09-28 현 편입 FR(#683) 어느 쪽 모집단에도 없던 F6 후보
SELECT m.universe_code, m.effective_from, s.ticker, m.effective_to,
  (SELECT max(f.created_at)::date FROM financial_statement f WHERE f.stock_id=s.id) fin_last_created,
  (m.effective_from <= '2026-09-28' AND (m.effective_to IS NULL OR m.effective_to > '2026-09-28')) member_at_683
FROM universe_membership m JOIN stock s ON s.id=m.stock_id
WHERE m.origin='OBSERVED' AND m.effective_from > '2026-08-31'
  AND NOT EXISTS (SELECT 1 FROM universe_membership p WHERE p.stock_id=m.stock_id AND p.universe_code=m.universe_code AND p.effective_from < m.effective_from)
ORDER BY 2,1,3;
