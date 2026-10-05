-- 대표 교체로 재편입할 형제의 마지막 봉 · 현 편입 여부 · 최신일(10-02) 지표 행 수
SELECT s.ticker, (SELECT max(trade_date) FROM price_daily p WHERE p.stock_id=s.id) last_bar,
  EXISTS (SELECT 1 FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000' AND m.effective_to IS NULL) is_cur,
  (SELECT count(*) FROM indicator_value iv WHERE iv.stock_id=s.id AND iv.trade_date='2026-10-02') codes_1002,
  (SELECT max(effective_to) FROM universe_membership m WHERE m.stock_id=s.id AND m.universe_code='US_TOP1000') last_exit
FROM stock s WHERE s.market_code='US' AND s.ticker IN ('GOOG','GOOGL','TAP','TAP.A','PPL','PPLC','Z','ZG','RUSHA','RUSHB','BF.A','BF.B','MKC','MKC.V','WSO','WSO.B','MOG.A','MOG.B','BIO','BIO.B')
ORDER BY s.ticker;
