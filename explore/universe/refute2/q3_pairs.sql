-- 대표 교체 후보 쌍(옛 대표 · 새 대표 후보)의 상태: 편입 · 봉 · 재무 · 원장 · 업종 · 거버넌스
WITH t(ticker) AS (VALUES ('PPLC'),('PPL'),('BTSGU'),('BTSG'),('TAP.A'),('TAP'),('MOG.B'),('MOG.A'),('WSO.B'),('WSO'),
 ('BIO.B'),('BIO'),('BF.A'),('BF.B'),('MKC.V'),('MKC'),('RUSHB'),('RUSHA'),('ZG'),('Z'),('GOOG'),('GOOGL'),
 ('LBTYB'),('LBTYA'),('LBTYK'),('NWS'),('NWSA'),('LLYVK'),('LLYVA'),('FOXA'),('FOX'),('UHAL'),('UHAL.B'),('HEI'),('HEI.A'),('LEN'),('LEN.B'))
SELECT s.ticker, s.id, s.is_active,
  (SELECT string_agg(m.universe_code||':'||m.origin||'['||m.effective_from||','||coalesce(m.effective_to::text,'')||')', ' ' ORDER BY m.effective_from)
     FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000') AS mem,
  (SELECT min(trade_date)||'~'||max(trade_date)||' n='||count(*) FROM price_daily p WHERE p.stock_id=s.id) AS bars,
  (SELECT count(*)||' rows · max_created '||max(created_at)::date FROM financial_statement f WHERE f.stock_id=s.id) AS fin,
  (SELECT count(*) FROM corporate_action c WHERE c.stock_id=s.id) AS ca,
  (s.industry_code IS NOT NULL OR s.sic_code IS NOT NULL) AS has_ind,
  (SELECT count(*) FROM governance_report_row r WHERE r.stock_id=s.id) AS gov
FROM stock s JOIN t ON t.ticker=s.ticker WHERE s.market_code='US' ORDER BY s.ticker;
