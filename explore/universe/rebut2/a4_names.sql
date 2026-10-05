-- 결정 2의 네 문구(토큰 경계 · Java \b와 같은 ASCII 경계를 PG \m\M로 근사)와 그 변형이 US 이름에서 무엇을 잡나.
-- 모집단: market_code='US' 전 종목(활성 여부 · US_LISTED 현재 편입 여부를 함께).
WITH us AS (
  SELECT s.id, s.ticker, s.name, s.is_active, s.sic_code,
    EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_LISTED' AND m.effective_to IS NULL) listed,
    EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=s.id AND f.shares_outstanding>0) has_sh,
    EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000') ever_top
  FROM stock s WHERE s.market_code='US')
SELECT CASE
   WHEN upper(name) ~ '\mCORPORATE UNITS?\M' THEN '1 CORPORATE UNITS?'
   WHEN upper(name) ~ '\mEQUITY UNITS?\M' THEN '2 EQUITY UNITS?'
   WHEN upper(name) ~ '\mPREFERENCE\M' THEN '3 PREFERENCE'
   WHEN upper(name) ~ '\mCAPITAL SECURITIES\M' THEN '4 CAPITAL SECURITIES'
   WHEN upper(name) ~ 'PREFEREN' THEN '5 near:PREFEREN*'
   WHEN upper(name) ~ 'CAPITAL SECURIT' THEN '6 near:CAPITAL SECURIT*'
   WHEN upper(name) ~ 'EQUITY UNIT|CORPORATE UNIT' THEN '7 near:unit'
  END bucket, ticker, name, is_active, listed, has_sh, ever_top, sic_code
FROM us
WHERE upper(name) ~ 'PREFEREN|CAPITAL SECURIT|EQUITY UNIT|CORPORATE UNIT'
ORDER BY 1, ticker;
