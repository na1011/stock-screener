-- 새 대표(형제)와 옛 대표의 재무 원장 차이: 같은 CIK라 같은 원장이어야 한다. is_latest 행을 (기 · 기간 · 공시일)로 맞대
-- 핵심 컬럼 값이 다른 칸 수와, 한쪽에만 있는 행 수. 적재 시각(created_at 최대)도 함께.
WITH pairs(newt, oldt) AS (VALUES ('BTSG','BTSGU'),('TAP','TAP.A'),('PPL','PPLC'),('MOG.A','MOG.B'),('WSO','WSO.B'),('MKC','MKC.V'),('BF.B','BF.A'),('RUSHA','RUSHB'),('Z','ZG'),('NWSA','NWS'),('LBTYK','LBTYB'),('UHAL.B','UHAL')),
ids AS (SELECT p.newt, p.oldt, n.id nid, o.id oid FROM pairs p JOIN stock n ON n.ticker=p.newt AND n.market_code='US' JOIN stock o ON o.ticker=p.oldt AND o.market_code='US'),
fn AS (SELECT * FROM financial_statement WHERE is_latest),
cmp AS (
 SELECT i.newt, i.oldt, a.period_end, a.fiscal_period, b.period_end bpe,
  (a.revenue IS DISTINCT FROM b.revenue)::int + (a.net_income IS DISTINCT FROM b.net_income)::int + (a.total_equity IS DISTINCT FROM b.total_equity)::int
  + (a.operating_cash_flow IS DISTINCT FROM b.operating_cash_flow)::int + (a.shares_outstanding IS DISTINCT FROM b.shares_outstanding)::int
  + (a.operating_income IS DISTINCT FROM b.operating_income)::int + (a.total_assets IS DISTINCT FROM b.total_assets)::int + (a.net_income_controlling IS DISTINCT FROM b.net_income_controlling)::int
  + (a.total_equity_controlling IS DISTINCT FROM b.total_equity_controlling)::int + (a.gross_profit IS DISTINCT FROM b.gross_profit)::int AS diff10
 FROM ids i
 JOIN fn a ON a.stock_id=i.nid
 FULL JOIN fn b ON b.stock_id=i.oid AND b.period_end=a.period_end AND b.fiscal_period=a.fiscal_period AND b.published_at=a.published_at)
SELECT i.newt, i.oldt,
 (SELECT max(created_at)::date FROM financial_statement WHERE stock_id=i.nid) new_loaded,
 (SELECT max(created_at)::date FROM financial_statement WHERE stock_id=i.oid) old_loaded,
 (SELECT count(*) FROM fn WHERE stock_id=i.nid) new_latest_rows,
 (SELECT count(*) FROM fn WHERE stock_id=i.oid) old_latest_rows,
 (SELECT count(*) FROM fn a WHERE a.stock_id=i.nid AND NOT EXISTS (SELECT 1 FROM fn b WHERE b.stock_id=i.oid AND b.period_end=a.period_end AND b.fiscal_period=a.fiscal_period AND b.published_at=a.published_at)) only_new,
 (SELECT count(*) FROM fn b WHERE b.stock_id=i.oid AND NOT EXISTS (SELECT 1 FROM fn a WHERE a.stock_id=i.nid AND b.period_end=a.period_end AND b.fiscal_period=a.fiscal_period AND b.published_at=a.published_at)) only_old,
 (SELECT coalesce(sum(diff10),0) FROM cmp c WHERE c.newt=i.newt AND c.period_end IS NOT NULL AND c.bpe IS NOT NULL) diff_cells_matched
FROM ids i ORDER BY 1;
