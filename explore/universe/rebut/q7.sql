\pset format unaligned
\pset fieldsep '\t'
WITH pairs(grp, ticker) AS (VALUES
 ('BIO','BIO'),('BIO','BIO.B'),('BF','BF.A'),('BF','BF.B'),('TAP','TAP'),('TAP','TAP.A'),('MKC','MKC'),('MKC','MKC.V'),
 ('MOG','MOG.A'),('MOG','MOG.B'),('WSO','WSO'),('WSO','WSO.B'),('RUSH','RUSHA'),('RUSH','RUSHB'),('Z','Z'),('Z','ZG'),
 ('GOOG','GOOG'),('GOOG','GOOGL'),('PPL','PPL'),('PPL','PPLC')),
ids AS (SELECT p.grp, s.id, s.ticker FROM pairs p JOIN stock s ON s.market_code='US' AND s.ticker=p.ticker),
days AS (SELECT DISTINCT trade_date d FROM price_daily p JOIN ids ON ids.id=p.stock_id WHERE trade_date >= '2025-09-24'),
w AS (
  SELECT ids.grp, ids.ticker, days.d,
    (SELECT sum(p.close*p.volume) FROM price_daily p WHERE p.stock_id=ids.id AND p.trade_date > days.d - 10 AND p.trade_date <= days.d) dv,
    (SELECT p.close FROM price_daily p WHERE p.stock_id=ids.id AND p.trade_date > days.d - 10 AND p.trade_date <= days.d ORDER BY p.trade_date DESC LIMIT 1) px
  FROM ids CROSS JOIN days),
win AS (
  SELECT grp, d,
    (array_agg(ticker ORDER BY dv DESC NULLS LAST, ticker))[1] dv_win,
    (array_agg(ticker ORDER BY px DESC NULLS LAST, ticker))[1] px_win,
    CASE WHEN count(dv)=count(*) THEN min(dv)/nullif(max(dv),0) END dv_ratio, bool_and(dv IS NOT NULL) fullw
  FROM w GROUP BY grp, d),
fl AS (SELECT grp, d, dv_win, px_win, dv_ratio, fullw,
   lag(dv_win) OVER (PARTITION BY grp ORDER BY d) pdv, lag(px_win) OVER (PARTITION BY grp ORDER BY d) ppx FROM win)
SELECT grp, count(*) days,
  count(*) FILTER (WHERE dv_win IS DISTINCT FROM pdv AND pdv IS NOT NULL) dv_flips,
  count(*) FILTER (WHERE px_win IS DISTINCT FROM ppx AND ppx IS NOT NULL) px_flips,
  string_agg(DISTINCT dv_win, ',') dv_winners, string_agg(DISTINCT px_win, ',') px_winners,
  round(max(dv_ratio)::numeric,3) max_minor_over_major
FROM fl WHERE fullw GROUP BY grp ORDER BY grp;
