-- d0b6ca4f(09-28 01:08 · 6-K) 커밋이 명시한 종목의 편입 이력과 #683(09-28 01:14) 포함 여부
SELECT s.ticker,
  string_agg(m.origin||'['||m.effective_from||','||coalesce(m.effective_to::text,'')||')', ' ' ORDER BY m.effective_from) rows_,
  bool_or(m.effective_from <= '2026-09-28' AND (m.effective_to IS NULL OR m.effective_to > '2026-09-28')) current_at_0928,
  (SELECT max(created_at)::timestamp(0) FROM financial_statement f WHERE f.stock_id=s.id) last_created
FROM stock s JOIN universe_membership m ON m.stock_id=s.id AND m.universe_code='US_TOP1000'
WHERE s.ticker IN ('ICLR','AER','BNT','DSGX','TSEM','GLNG','SMMT') AND s.is_active
GROUP BY s.id, s.ticker ORDER BY 1;
