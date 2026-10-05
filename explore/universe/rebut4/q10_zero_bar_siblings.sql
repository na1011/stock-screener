-- B-1 함정: 봉 0 새 대표 후보는 토요일 꼬리에서 「시세만」 초기 적재(skipFinancial)를 탄다 — 재무 0행이 아니라서 FINANCIAL 축에 안 걸린다.
-- 「초기 적재가 종목 성공마다 쓴다」를 종목 단위로 구현하면 이들이 토요일에 동기화로 찍혀 F6에서 빠진다.
SELECT s.ticker, s.is_active,
  (SELECT count(*) FROM price_daily p WHERE p.stock_id=s.id) bars,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id) fin_rows,
  (SELECT max(created_at)::timestamp(0) FROM financial_statement f WHERE f.stock_id=s.id) fin_last_created,
  (SELECT count(*) FROM governance_fact g WHERE g.stock_id=s.id) gov_facts,
  (SELECT string_agg(universe_code||'['||effective_from||','||coalesce(effective_to::text,'')||')',' ') FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code<>'US_LISTED') top_rows
FROM stock s WHERE s.market_code='US' AND s.ticker IN ('BTSG','NWSA','UHAL.B','LBTYA','LBTYK','LLYVA','HEI.A','LEN.B','FOX') ORDER BY 1;
