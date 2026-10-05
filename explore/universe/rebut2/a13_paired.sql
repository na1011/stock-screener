-- 다른 CIK인데 같은 경제 실체(쌍둥이 상장) 후보가 둘 다 지금 명부에 있는가 — 같은 뿌리(같은 회사 두 번)의 다른 형태 탐색
SELECT s.ticker, s.name, (SELECT m.effective_from FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000' AND m.effective_to IS NULL) cur_from,
  (SELECT f.shares_outstanding FROM financial_statement f WHERE f.stock_id=s.id AND f.shares_outstanding>0 ORDER BY f.period_end DESC, f.published_at DESC LIMIT 1) sh,
  (SELECT max(f.net_income) FILTER (WHERE f.fiscal_period='FY' AND f.fiscal_year=2025 AND f.is_latest) FROM financial_statement f WHERE f.stock_id=s.id) ni_fy25
FROM stock s WHERE s.market_code='US' AND s.ticker IN ('CCL','CUK','BEP','BEPC','BIP','BIPC','BBU','BBUC','BN','BNT','BAM','UL','RIO','BHP','BBVA','PUK','NWG')
ORDER BY 1;
