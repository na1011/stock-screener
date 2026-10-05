SET statement_timeout='60s';
WITH t(ticker) AS (SELECT unnest(string_to_array('AIXC ASND BRR COLA COLAR COLAU DBRG DDS DDT DOMO DRCT ET FGNX FGNXP FSEA GETY GFAIW GWH HVII HVIIR HVIIU HWH IPEX IPEXR IPEXU JSPRW KLXER NHIC NSTS NTRBW PDYNW RILYN SBXD SUN SUNC TBPH TWOD USAC VACI VRME WALD WALDW WKEY',' '))),
latest AS (
  SELECT DISTINCT ON (f.stock_id) f.stock_id, f.shares_outstanding, f.period_end, f.published_at, f.share_basis_multiple, f.share_basis_status
  FROM financial_statement f JOIN stock s ON s.id=f.stock_id
  WHERE s.market_code='US' AND s.is_active AND f.published_at <= DATE '2026-10-02' AND f.shares_outstanding IS NOT NULL AND f.shares_outstanding>0 AND f.period_end >= DATE '2024-10-02'
  ORDER BY f.stock_id, f.period_end DESC, f.published_at DESC, f.revision_no DESC)
SELECT s.ticker, s.listing_exchange, s.sic_code, left(s.name,50) name, s.created_at::date,
  l.shares_outstanding, l.period_end, l.share_basis_status,
  (SELECT count(*) FROM financial_statement f WHERE f.stock_id=s.id) fs_rows,
  EXISTS (SELECT 1 FROM financial_statement m JOIN stock g ON g.id=m.stock_id JOIN market gm ON gm.code=g.market_code WHERE m.stock_id=s.id AND m.currency_code<>gm.currency_code) cur_mis,
  (SELECT string_agg(um.universe_code||':'||um.effective_from||'~'||coalesce(um.effective_to::text,''),',') FROM universe_membership um WHERE um.stock_id=s.id) memb,
  (SELECT max(trade_date) FROM price_daily p WHERE p.stock_id=s.id) last_px
FROM t JOIN stock s ON s.ticker=t.ticker AND s.market_code='US'
LEFT JOIN latest l ON l.stock_id=s.id
ORDER BY s.ticker;
