-- 기존 NON_COMMON_NAME(main)이 이미 자르는 「주식수 있는」 미국 상장 이름 — R3를 c의 이름 제외 전체로 읽으면 이 집합이 섞인다.
-- 근사: 주식수 술어 = financial_statement.shares_outstanding>0 · 기말 2년 안 · 통화 일치(행 전부) · SIC 6798/6770 아님 · US_LISTED 현재 편입. 이름은 DB(08-29).
WITH listed AS (SELECT s.* FROM stock s WHERE s.market_code='US' AND s.is_active
   AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_LISTED' AND m.effective_to IS NULL)),
sh AS (SELECT l.* FROM listed l WHERE EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=l.id AND f.shares_outstanding>0 AND f.period_end >= current_date - interval '2 years')
   AND NOT EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=l.id AND f.currency_code<>'USD')
   AND coalesce(l.sic_code,'') NOT IN ('6798','6770'))
SELECT CASE WHEN upper(name) ~ '\mDEPOSITARY\M' THEN 'DEPOSITARY' ELSE 'other-existing' END k, count(*),
       string_agg(ticker, ',' ORDER BY ticker) FILTER (WHERE upper(name) ~ '\mDEPOSITARY\M' AND upper(name) !~ '\mPREFERRED\M|\mNOTES?\M') adr_like
FROM sh WHERE upper(name) ~ '\m(PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?)\M'
GROUP BY 1;
