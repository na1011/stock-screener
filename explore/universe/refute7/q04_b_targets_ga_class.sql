-- B가 FR을 돌릴 미국 대상(첫 평일 13 + A의 새 대표 11) 중 최신 기말이 (가) 모양(2026-03-01~04-10)인 것:
-- B가 FR 성공 표지를 쓰고도 6월 분기가 안 들어올 수 있는 종목(companyfacts 결손이면 FR이 못 닫는다).
SET statement_timeout='60s';
WITH t(ticker, src) AS (VALUES
 ('AGX','B13'),('AXTI','B13'),('BB','B13'),('FTDR','B13'),('GVA','B13'),('IOVA','B13'),('KLIC','B13'),('KOD','B13'),('LAUR','B13'),('NAVN','B13'),('NJR','B13'),('TFX','B13'),('VSH','B13'),
 ('BF.B','A11'),('BTSG','A11'),('MKC','A11'),('MOG.A','A11'),('NWSA','A11'),('PPL','A11'),('RUSHA','A11'),('TAP','A11'),('UHAL.B','A11'),('WSO','A11'),('Z','A11'))
SELECT t.src, t.ticker, s.id IS NOT NULL in_stock, f.pe max_period_end, f.fp, f.pub max_published,
       CASE WHEN f.pe BETWEEN date '2026-03-01' AND date '2026-04-10' THEN 'GA_SHAPE' ELSE '' END ga
FROM t LEFT JOIN stock s ON s.ticker=t.ticker AND s.market_code='US'
LEFT JOIN LATERAL (SELECT max(period_end) pe, max(published_at) pub,
     (array_agg(fiscal_period ORDER BY period_end DESC))[1] fp FROM financial_statement WHERE stock_id=s.id) f ON true
ORDER BY ga DESC, t.src, t.ticker;
