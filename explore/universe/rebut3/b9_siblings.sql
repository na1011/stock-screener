-- 10-10 대표 교체로 들어올 형제의 편입 이력(US_TOP1000)과 F6(V1) 해당 여부
WITH win AS (SELECT min(trade_date) lo, max(trade_date) hi FROM indicator_trade_date WHERE market_code='US')
SELECT s.ticker, s.is_active,
  (SELECT string_agg(m.origin||'['||m.effective_from||','||coalesce(m.effective_to::text,'')||')', ' ' ORDER BY m.effective_from)
     FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000') top1000_rows,
  NOT EXISTS (SELECT 1 FROM universe_membership m, win WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000'
              AND (m.effective_to IS NULL OR (m.effective_from<=win.hi AND m.effective_to>win.lo))) f6_if_enters,
  (SELECT max(f.created_at)::date FROM financial_statement f WHERE f.stock_id=s.id) fin_last_created,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id AND f.is_latest) fin_latest
FROM stock s
WHERE s.market_code='US' AND s.ticker IN ('BTSG','NWSA','UHAL.B','FOX','HEI.A','LEN.B','LBTYA','LBTYK','LLYVA',
  'TAP','PPL','Z','BF.B','MKC','WSO','MOG.A','RUSHA','BIO','GOOGL')
ORDER BY f6_if_enters DESC, s.ticker;
