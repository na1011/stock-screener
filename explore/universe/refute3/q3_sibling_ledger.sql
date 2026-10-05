-- 같은 CIK 클래스 쌍(현 대표 · 형제)의 기업행위 원장 · 봉 수 · 재무 행 수 · 지금 편입 여부
WITH t(ticker) AS (VALUES ('LBTYB'),('LBTYA'),('LBTYK'),('NWS'),('NWSA'),('LLYVK'),('LLYVA'),('FOXA'),('FOX'),('UHAL'),('UHAL.B'),('HEI'),('HEI.A'),('LEN'),('LEN.B'),
 ('TAP'),('TAP.A'),('MOG.A'),('MOG.B'),('WSO'),('WSO.B'),('BIO'),('BIO.B'),('BF.A'),('BF.B'),('MKC'),('MKC.V'),('GOOG'),('GOOGL'),('Z'),('ZG'),('RUSHA'),('RUSHB'),('PPL'),('PPLC'),('BTSG'),('BTSGU'))
SELECT t.ticker, s.id, s.market_code, s.is_active,
  (SELECT count(*) FROM price_daily p WHERE p.stock_id=s.id) bars,
  (SELECT min(trade_date) FROM price_daily p WHERE p.stock_id=s.id) first_bar,
  (SELECT max(trade_date) FROM price_daily p WHERE p.stock_id=s.id) last_bar,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id) fin,
  (SELECT string_agg(ca.effective_date||':'||ca.type||':'||ca.status||coalesce(':pr='||round(ca.price_ratio,4),''), ' ; ' ORDER BY ca.effective_date) FROM corporate_action ca WHERE ca.stock_id=s.id) ledger,
  (SELECT string_agg(m.origin||'['||m.effective_from||','||coalesce(m.effective_to::text,'')||')', ' ' ORDER BY m.effective_from) FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000') mem
FROM t LEFT JOIN stock s ON s.ticker=t.ticker AND s.market_code='US'
ORDER BY t.ticker;
