\i base.sql
\pset format unaligned
\pset fieldsep '\t'
SELECT count(*) FILTER (WHERE upper(name) ~ '\mUNITS?\M') unit_all, count(*) FILTER (WHERE upper(name) ~ '\mUNITS?\M' AND sic_code='6770') unit_6770 FROM listed;
SELECT l.ticker, l.name FROM listed l JOIN topwin t ON t.stock_id=l.id WHERE upper(l.name) ~ '\mUNITS?\M' ORDER BY 1;
-- window roster members whose names hint non-common (beyond units)
SELECT l.ticker, l.name, lc.cik FROM listed l JOIN topwin t ON t.stock_id=l.id JOIN lc ON lc.id=l.id
WHERE upper(l.name) ~ '(%|\mDUE\M|CAPITAL SECURIT|SUBORDINATED NOTE|DEPOSITOR|CONVERTIBLE|MANDATORY|PURCHASE CONTRACT|PREFERENCE|CUMULATIVE|PERPETUAL|TRUST PREFERRED|WHEN ISSUED|\mWI\M|TRACKING)' ORDER BY 1;
