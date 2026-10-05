\i base.sql
SELECT 'listed' k, count(*) FROM listed UNION ALL SELECT 'listed_with_cik', count(*) FROM lc WHERE cik IS NOT NULL
UNION ALL SELECT 'sc_all', count(*) FROM sc UNION ALL SELECT 'sc_listed', count(*) FROM sc JOIN listed l ON l.id=sc.stock_id
UNION ALL SELECT 'sc_listed_nocik', count(*) FROM sc JOIN lc ON lc.id=sc.stock_id WHERE lc.cik IS NULL
UNION ALL SELECT 'sc_pass_gate_listed', count(*) FROM sc JOIN listed l ON l.id=sc.stock_id WHERE NOT cur_mis AND coalesce(sc.sic_code,'') NOT IN ('6798','6770')
UNION ALL SELECT 'top_open', count(*) FROM top UNION ALL SELECT 'topwin', count(*) FROM topwin;
