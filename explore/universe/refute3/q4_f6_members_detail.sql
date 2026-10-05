-- 술어 모집단 7의 재무 행 · 최신 기말 · 최대 created_at · 마지막 FR 대상 여부(실행 note에 티커가 안 남으므로 created_at만)
SELECT s.ticker, s.market_code, s.name,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id) fin_rows,
  (SELECT max(f.period_end) FROM financial_statement f WHERE f.stock_id=s.id) max_period_end,
  (SELECT max(f.created_at)::date FROM financial_statement f WHERE f.stock_id=s.id) max_created,
  (SELECT min(p.trade_date) FROM price_daily p WHERE p.stock_id=s.id) first_bar
FROM stock s WHERE (s.ticker, s.market_code) IN (('950260','KOSDAQ'),('092870','KOSDAQ'),('CVI','US'),('INSW','US'),('GRAL','US'),('IOVA','US'),('KOD','US'))
ORDER BY 2,1;
