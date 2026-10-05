\i base.sql
\pset format unaligned
\pset fieldsep '\t'
-- candidates (shares pass gate, name not excluded by current NON_COMMON_NAME) whose name hints a non-common security
WITH cand AS (
  SELECT lc.*, sc.shares FROM lc JOIN sc ON sc.stock_id=lc.id
  WHERE NOT sc.cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')
    AND upper(lc.name) !~ '\m(PREFERRED|PFD|WARRANTS?|RIGHTS?|DEPOSITARY|NOTES?|DEBENTURES?|BONDS?|ETFS?|FUNDS?)\M')
SELECT c.cik, c.ticker, c.name, round(c.shares) shares,
  (SELECT count(*) FROM cand x WHERE x.cik=c.cik) n_cand_same_cik,
  (c.id IN (SELECT stock_id FROM top)) in_top, (c.id IN (SELECT stock_id FROM topwin)) in_win
FROM cand c
WHERE upper(c.name) ~ '(%|\mDUE\M|\mSERIES\M|\mUNITS?\M|CAPITAL SECURIT|SUBORDINAT|DEPOSITOR|CONVERTIBLE|MANDATORY|PURCHASE CONTRACT|INCOME EQUITY|TRUST\M|CUMULATIVE|PERPETUAL|PREFERENCE|SENIOR|EQUITY SECURITY|TANGIBLE|PREMIUM|QUIPS|TRUPS|PIERS|ADJUSTABLE|FIXED|FLOATING|RESET|REDEEMABLE|CERTIFICATE|RECEIPT|INTEREST\M|CAPITAL TRUST|TRACKING|GROUP COMMON|WHEN ISSUED|\mWI\M)'
ORDER BY c.cik, c.ticker;
