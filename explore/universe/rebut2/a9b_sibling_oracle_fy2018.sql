-- a9의 FY>=2018 판(꼬리 FR 8년 창 = asOf.year-8)으로 좁힌 것 — R6 형제 오라클이 0으로 떨어져야 할 범위
-- 같은 CIK 형제의 재무 행 집합 대조 — 대표 교체로 들 종목(새 대표 후보)과 지금 대표(옛 대표)가 같은 원장(companyfacts)에서 왔는데
-- 판(기간·공시일·개정) 집합이 얼마나 다른가. R6 「새 대표 재무 최신 판 = 소스 최신」의 독립 오라클 후보.
WITH pairs(newrep, oldrep) AS (VALUES ('BTSG','BTSGU'),('NWSA','NWS'),('UHAL.B','UHAL'),('PPL','PPLC'),('TAP','TAP.A'),
  ('MOG.A','MOG.B'),('WSO','WSO.B'),('BF.B','BF.A'),('MKC','MKC.V'),('RUSHA','RUSHB'),('Z','ZG'),('FOX','FOXA'),('LEN.B','LEN'),('HEI.A','HEI'),('LLYVA','LLYVK'),('LBTYA','LBTYB')),
k AS (
  SELECT p.newrep, p.oldrep, s.ticker, f.fiscal_year, f.fiscal_period, f.published_at, f.revision_no, f.is_latest,
         md5(concat_ws('|', f.revenue, f.net_income, f.total_assets, f.total_equity, f.operating_cash_flow, f.shares_outstanding, f.net_income_controlling, f.total_equity_controlling)) h
  FROM pairs p JOIN stock s ON s.market_code='US' AND s.ticker IN (p.newrep, p.oldrep)
  JOIN financial_statement f ON f.stock_id=s.id AND f.fiscal_year >= 2018)
SELECT newrep, oldrep,
  count(*) FILTER (WHERE ticker=newrep) new_rows, count(*) FILTER (WHERE ticker=oldrep) old_rows,
  (SELECT count(*) FROM k a WHERE a.newrep=k0.newrep AND a.ticker=a.oldrep AND NOT EXISTS (SELECT 1 FROM k b WHERE b.newrep=a.newrep AND b.ticker=b.newrep AND (b.fiscal_year,b.fiscal_period,b.published_at)=(a.fiscal_year,a.fiscal_period,a.published_at))) vintages_only_in_old,
  (SELECT count(*) FROM k a WHERE a.newrep=k0.newrep AND a.ticker=a.newrep AND NOT EXISTS (SELECT 1 FROM k b WHERE b.newrep=a.newrep AND b.ticker=b.oldrep AND (b.fiscal_year,b.fiscal_period,b.published_at)=(a.fiscal_year,a.fiscal_period,a.published_at))) vintages_only_in_new,
  (SELECT count(*) FROM k a JOIN k b ON b.newrep=a.newrep AND a.ticker=a.newrep AND b.ticker=b.oldrep AND (b.fiscal_year,b.fiscal_period,b.published_at,b.revision_no)=(a.fiscal_year,a.fiscal_period,a.published_at,a.revision_no) AND a.h<>b.h WHERE a.newrep=k0.newrep) same_vintage_diff_values,
  min(fiscal_year) FILTER (WHERE ticker=newrep) new_min_fy, min(fiscal_year) FILTER (WHERE ticker=oldrep) old_min_fy
FROM k k0 GROUP BY newrep, oldrep ORDER BY newrep;
