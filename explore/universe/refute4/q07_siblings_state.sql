-- 봉 0 형제(새 대표 후보)의 원자료 상태 — 토 꼬리가 어느 축으로 잡나(시세만 · 재무 · 거버넌스)
SELECT s.ticker, s.is_active,
  (SELECT count(*) FROM price_daily p WHERE p.stock_id=s.id) bars,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id) fin_rows,
  (SELECT max(f.created_at)::date FROM financial_statement f WHERE f.stock_id=s.id) fin_last_created,
  (SELECT count(*) FROM governance_fact g WHERE g.stock_id=s.id) gov_facts,
  (SELECT count(*) FROM corporate_action c WHERE c.stock_id=s.id) ca_rows,
  (SELECT string_agg(universe_code||'['||effective_from||','||coalesce(effective_to::text,'')||')',' ') FROM universe_membership m WHERE m.stock_id=s.id AND universe_code<>'US_LISTED') memb
FROM stock s WHERE s.market_code='US' AND s.ticker IN ('BTSG','NWSA','UHAL.B','LBTYA','LBTYK','LLYVA','HEI.A','LEN.B','FOX','LLYVK','HEI','LEN','FOXA','NWS','UHAL','LBTYB')
ORDER BY s.ticker;
