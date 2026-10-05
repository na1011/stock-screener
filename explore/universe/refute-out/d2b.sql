-- 결정 2(주식수를 CIK로 잇기)의 수혜 모집단: 자기 주식수가 없는(어댑터 술어 복제 · asOf 2026-10-02) 미국 상장 중
-- 같은 CIK 형제에 주식수가 있는 것. 이름 축(현행 + 계획 추가)과 SIC 축 적용.
CREATE TEMP TABLE cik(cik bigint, ticker text, exch text);
\copy cik FROM 'cik.csv' CSV
WITH latest AS (
 SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding, f.share_basis_status
 FROM financial_statement f JOIN stock s ON s.id=f.stock_id
 WHERE s.market_code='US' AND s.is_active AND f.published_at <= '2026-10-02' AND f.shares_outstanding > 0 AND f.period_end >= '2024-10-02'
 ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC),
sh AS (SELECT stock_id, shares_outstanding FROM latest WHERE share_basis_status IS DISTINCT FROM 'UNRESOLVED'),
listed AS (
 SELECT s.id, s.ticker, s.name, s.sic_code FROM stock s
 WHERE s.market_code='US' AND s.is_active
   AND EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_LISTED' AND m.effective_to IS NULL)),
j AS (SELECT c.cik, l.*, sh.shares_outstanding sh FROM listed l JOIN cik c ON c.ticker = replace(l.ticker,'.','-') LEFT JOIN sh ON sh.stock_id=l.id)
SELECT j.cik, j.ticker, j.name, j.sic_code,
       (SELECT string_agg(k.ticker||':'||k.sh, ' ') FROM j k WHERE k.cik=j.cik AND k.sh IS NOT NULL) donors
FROM j
WHERE j.sh IS NULL
  AND EXISTS (SELECT 1 FROM j k WHERE k.cik=j.cik AND k.sh IS NOT NULL)



ORDER BY j.cik, j.ticker;
