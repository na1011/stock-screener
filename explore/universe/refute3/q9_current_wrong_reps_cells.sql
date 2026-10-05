-- 지금 편입 중인 「옛 규칙 대표」(계획 §1이 든 것) · 최신 지표일의 코드 수 = 하루에 쌓이는 다른 증권 칸
WITH t(ticker) AS (VALUES ('PPLC'),('BTSGU'),('TAP.A'),('MOG.B'),('WSO.B'),('BIO.B'),('BF.A'),('MKC.V'),('ZG'),('RUSHB'),('GOOG'),('NWS'),('UHAL'),('LLYVK'),('LBTYB'))
SELECT t.ticker, (m.stock_id IS NOT NULL) current_member,
  (SELECT count(*) FROM indicator_value iv WHERE iv.stock_id=s.id AND iv.trade_date=(SELECT max(trade_date) FROM indicator_trade_date WHERE market_code='US')) codes_latest
FROM t JOIN stock s ON s.ticker=t.ticker AND s.market_code='US'
LEFT JOIN universe_membership m ON m.stock_id=s.id AND m.universe_code='US_TOP1000' AND m.effective_to IS NULL
ORDER BY 2 DESC, 1;
