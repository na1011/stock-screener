-- 쌍의 원장 행
SELECT s.ticker, c.effective_date, c.type, c.ratio, c.price_ratio, c.status, c.detection_source, c.applied_at::date, left(c.note,120) note
FROM corporate_action c JOIN stock s ON s.id=c.stock_id
WHERE s.market_code='US' AND s.ticker IN ('LBTYB','LBTYA','LBTYK','LEN','LEN.B','GOOG','GOOGL','MKC','MKC.V','RUSHA','RUSHB','UHAL','UHAL.B','HEI','HEI.A','FOX','FOXA','NWS','NWSA','BTSG','BTSGU','LLYVK','LLYVA')
ORDER BY s.ticker, c.effective_date;
-- 판정이 필요한 상태(대기) 수와 형태
SELECT status, type, count(*) FROM corporate_action GROUP BY 1,2 ORDER BY 1,2;
