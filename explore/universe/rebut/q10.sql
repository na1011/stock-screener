\i base.sql
\pset format unaligned
\pset fieldsep '\t'
-- roster (open) members without CIK, or sharing CIK with another listed ticker that is not a candidate
SELECT 'top_nocik' k, count(*) FROM top JOIN lc ON lc.id=top.stock_id WHERE lc.cik IS NULL
UNION ALL SELECT 'topwin_nocik', count(*) FROM topwin JOIN lc ON lc.id=topwin.stock_id WHERE lc.cik IS NULL
UNION ALL SELECT 'top_not_listed', count(*) FROM top WHERE stock_id NOT IN (SELECT id FROM listed);
-- known multi-class tickers: presence, CIK, shares
SELECT s.ticker, s.name, coalesce(c1.cik,c2.cik) cik, round(sc.shares) shares, sc.cur_mis, (s.id IN (SELECT stock_id FROM top)) in_top, (s.id IN (SELECT id FROM listed)) listed
FROM stock s LEFT JOIN cik c1 ON c1.ticker=s.ticker LEFT JOIN cik c2 ON c2.ticker=replace(s.ticker,'.','-') LEFT JOIN sc ON sc.stock_id=s.id
WHERE s.market_code='US' AND s.ticker IN ('GEF','GEF.B','CWEN','CWEN.A','HVT','HVT.A','CRD.A','CRD.B','LGF.A','LGF.B','LION','FWONA','FWONK','LBRDA','LBRDK','QVCGA','QVCGB','AKO.A','AKO.B','BRK.A','BRK.B','PBR','PBR.A','BH','BH.A','JW.A','JW.B','EBR','EBR.B','CIG','CIG.C','STZ','LSXMA','LSXMK','DJCO','MKC','BELFA','BELFB','HUBB','NWSA','NWS','FOX','FOXA','UHAL','UHAL.B','HEI','HEI.A','LEN','LEN.B','LBTYA','LBTYK','LBTYB','UA','UAA','BATRA','BATRK','LLYVA','LLYVK','DLB','TFSL','INGM','CMCSA','META','SNAP','DELL','BF.A','BF.B','MOG.A','MOG.B','RUSHA','RUSHB','GOOG','GOOGL','Z','ZG','TAP','TAP.A','WSO','WSO.B','BIO','BIO.B','MKC.V','SIRI','TRIP','LTRPA','LTRPB','GTN','GTN.A','AGM','AGM.A','GLIBA','GLIBK','SENEA','SENEB','KELYA','KELYB','CENT','CENTA','RDI','RDIB','EXPGY','ROL','SEB','HOV','HOVNP','NMRK','PROSY','BIP','BIPC','BEP','BEPC','BN','BNT','BAM','BBU','BBUC','RY','TD','UBS','BEPI')
ORDER BY cik NULLS FIRST, s.ticker;
