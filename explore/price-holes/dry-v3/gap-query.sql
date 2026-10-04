\timing on
SET statement_timeout = '60s';
WITH members AS (
    SELECT m.stock_id, s.market_code, s.ticker, m.effective_from, m.effective_to
    FROM universe_membership m
    JOIN stock s ON s.id = m.stock_id
    JOIN universe u ON u.code = m.universe_code AND u.is_active
    WHERE s.is_active AND s.market_code IN ('KOSDAQ','KOSPI','US')
      AND (m.effective_to IS NULL
           OR (m.effective_from <= (SELECT max(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)
               AND m.effective_to > (SELECT min(d.trade_date) FROM indicator_trade_date d WHERE d.market_code = s.market_code)))
),
current_counts AS MATERIALIZED (
    SELECT market_code, count(DISTINCT stock_id) AS n FROM members WHERE effective_to IS NULL GROUP BY market_code
),
cal AS MATERIALIZED (
    SELECT market_code, trade_date, row_number() OVER (PARTITION BY market_code ORDER BY trade_date) AS seq
    FROM (SELECT index_code AS market_code, trade_date FROM market_index_daily
          WHERE index_code IN ('KOSDAQ','KOSPI','US') AND trade_date <= :'target'::date
          UNION
          SELECT s.market_code, p.trade_date FROM price_daily p JOIN stock s ON s.id = p.stock_id
          JOIN current_counts cm ON cm.market_code = s.market_code
          WHERE s.market_code IN ('KOSDAQ','KOSPI','US') AND p.trade_date <= :'target'::date
          GROUP BY s.market_code, p.trade_date
          HAVING count(*) * 2 >= max(cm.n)) d
),
anchor AS (SELECT market_code, max(seq) AS t_seq FROM cal GROUP BY market_code),
spans AS MATERIALIZED (
    SELECT mb.stock_id, mb.market_code, mb.ticker,
           bool_or(mb.effective_to IS NULL) AS current_member,
           min(greatest(mb.effective_from, w.lo)) AS member_from,
           max(coalesce(mb.effective_to, :'target'::date + 1)) AS member_until
    FROM members mb
    JOIN (SELECT market_code, min(trade_date) AS lo FROM indicator_trade_date GROUP BY market_code) w
      ON w.market_code = mb.market_code
    GROUP BY mb.stock_id, mb.market_code, mb.ticker
),
ranges AS MATERIALIZED (
    SELECT b.stock_id, b.market_code, b.ticker, b.current_member,
           CASE WHEN b.first_member_day < b.first_bar THEN b.read_floor
                ELSE greatest(b.first_bar, b.read_floor) END AS lower_,
           b.member_until - 1 AS upper_
    FROM (SELECT sp.*,
                 (SELECT min(p.trade_date) FROM price_daily p WHERE p.stock_id = sp.stock_id) AS first_bar,
                 (SELECT min(c.trade_date) FROM cal c WHERE c.market_code = sp.market_code AND c.trade_date >= sp.member_from) AS first_member_day,
                 (SELECT c.trade_date FROM cal c WHERE c.market_code = sp.market_code AND c.seq = greatest(1, a.t_seq - :readbars + 1)) AS read_floor
          FROM spans sp JOIN anchor a ON a.market_code = sp.market_code) b
    WHERE b.first_bar IS NOT NULL AND b.first_member_day IS NOT NULL
),
holes AS (
    SELECT r.stock_id, r.market_code, r.ticker, r.current_member, c.trade_date, c.seq
    FROM ranges r
    JOIN cal c ON c.market_code = r.market_code AND c.trade_date BETWEEN r.lower_ AND r.upper_
    WHERE NOT (r.current_member AND c.trade_date >= :'wstart'::date)
      AND NOT EXISTS (SELECT 1 FROM price_daily p WHERE p.stock_id = r.stock_id AND p.trade_date = c.trade_date)
)
SELECT stock_id, market_code, ticker, current_member, min(trade_date) AS gap_from, max(trade_date) AS gap_to, count(*) AS days
FROM (SELECT holes.*, seq - row_number() OVER (PARTITION BY stock_id ORDER BY seq) AS grp FROM holes) h
GROUP BY stock_id, market_code, ticker, current_member, grp
ORDER BY market_code, ticker, gap_from;
