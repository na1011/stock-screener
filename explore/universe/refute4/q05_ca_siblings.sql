-- CIK 형제(새 대표 후보)와 옛 대표의 기업행위 원장 — 새 대표가 들어오면 14:00 수집이 무엇을 만들까
SELECT s.ticker, ca.id, ca.type, ca.status, ca.effective_date, ca.ratio, ca.price_ratio, ca.detection_source, left(coalesce(ca.note,''),100) note
FROM corporate_action ca JOIN stock s ON s.id=ca.stock_id
WHERE s.market_code='US' AND s.ticker IN ('LBTYA','LBTYB','LBTYK','NWS','NWSA','UHAL','UHAL.B','FOX','FOXA','HEI','HEI.A','LEN','LEN.B','LLYVA','LLYVK','BTSG','BTSGU','PPL','PPLC','TAP','TAP.A','MOG.A','MOG.B','WSO','WSO.B','BIO','BIO.B','BF.A','BF.B','MKC','MKC.V','Z','ZG','RUSHA','RUSHB','GOOG','GOOGL')
ORDER BY s.ticker, ca.effective_date;
-- 판정 대기(OBSERVED) · UNSUPPORTED 현황
SELECT status, type, count(*) FROM corporate_action GROUP BY 1,2 ORDER BY 1,2;
