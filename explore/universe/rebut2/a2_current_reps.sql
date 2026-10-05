-- 대표 교체 후보(계획 §1·v1 반증) 중 지금(effective_to IS NULL) US_TOP1000 편입인 것과 그 형제의 상태
WITH t(grp, ticker) AS (VALUES
 ('PPL','PPLC'),('PPL','PPL'),('BTSG','BTSGU'),('BTSG','BTSG'),('BIO','BIO.B'),('BIO','BIO'),('BF','BF.A'),('BF','BF.B'),
 ('TAP','TAP.A'),('TAP','TAP'),('MKC','MKC.V'),('MKC','MKC'),('MOG','MOG.B'),('MOG','MOG.A'),('WSO','WSO.B'),('WSO','WSO'),
 ('RUSH','RUSHB'),('RUSH','RUSHA'),('Z','ZG'),('Z','Z'),('GOOG','GOOG'),('GOOG','GOOGL'),
 ('LBTY','LBTYB'),('LBTY','LBTYA'),('LBTY','LBTYK'),('NWS','NWS'),('NWS','NWSA'),('LLYV','LLYVK'),('LLYV','LLYVA'),
 ('FOX','FOXA'),('FOX','FOX'),('UHAL','UHAL'),('UHAL','UHAL.B'),('HEI','HEI'),('HEI','HEI.A'),('LEN','LEN'),('LEN','LEN.B'))
SELECT t.grp, t.ticker, s.id, s.is_active,
  (SELECT string_agg(m.origin||':'||m.effective_from||'~'||coalesce(m.effective_to::text,''),' ' ORDER BY m.effective_from) FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000') top,
  (SELECT min(trade_date)||'..'||max(trade_date)||' n='||count(*) FROM price_daily p WHERE p.stock_id=s.id) bars,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id) fin_rows,
  (SELECT max(f.created_at)::date FROM financial_statement f WHERE f.stock_id=s.id) fin_last_created
FROM t LEFT JOIN stock s ON s.market_code='US' AND s.ticker=t.ticker ORDER BY t.grp, t.ticker;
