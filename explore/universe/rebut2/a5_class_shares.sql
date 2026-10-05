-- 같은 CIK 클래스들의 저장 주식수(최신 기말·최신 판) — 「발행사 단위 값」인지 클래스별인지
SELECT s.ticker, f.period_end, f.published_at, f.revision_no, f.shares_outstanding, f.created_at::date created
FROM stock s JOIN LATERAL (
  SELECT * FROM financial_statement f WHERE f.stock_id=s.id AND f.shares_outstanding>0
  ORDER BY f.period_end DESC, f.published_at DESC, f.revision_no DESC LIMIT 1) f ON true
WHERE s.market_code='US' AND s.ticker IN ('FOX','FOXA','NWS','NWSA','LBTYA','LBTYB','LBTYK','LLYVA','LLYVK','HEI','HEI.A','LEN','LEN.B','UHAL','UHAL.B','GOOG','GOOGL','BTSG','BTSGU','PPL','PPLC','TAP','TAP.A','MOG.A','MOG.B','BF.A','BF.B')
ORDER BY 1;
