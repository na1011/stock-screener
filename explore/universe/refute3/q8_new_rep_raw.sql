-- 창 이력 없는 형제(10-10에 대표가 될 수 있는 것)의 원자료 축: 봉 · 재무 · 현금흐름 · 거버넌스 · 업종 · 원장
SELECT s.ticker, s.id,
  (SELECT count(*) FROM price_daily p WHERE p.stock_id=s.id) bars,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id) fin,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id AND f.operating_cash_flow IS NOT NULL) ocf,
  (SELECT count(*) FROM governance_report_row r WHERE r.stock_id=s.id) gov_rows,
  (SELECT count(*) FROM governance_fact g WHERE g.stock_id=s.id) gov_fact,
  s.industry_code, s.sic_code,
  (SELECT count(*) FROM corporate_action c WHERE c.stock_id=s.id) ca
FROM stock s WHERE s.market_code='US' AND s.ticker IN ('BTSG','NWSA','UHAL.B','LLYVA','FOX','HEI.A','LEN.B','LBTYA','LBTYK')
ORDER BY 1;
