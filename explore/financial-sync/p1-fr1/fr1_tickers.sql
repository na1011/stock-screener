-- 1부 첫 손 FR의 대상 = RS1 두 사본(보충 없음 rs1a · 보충 rs1b)에서 재무 행이 다른 미국 종목.
-- 종목마다 financial_statement 전 행의 서명(id·created_at 제외)을 내고, 두 사본에서 다른 티커를 고른다.
-- 사용: psql -d stock_screener_q4v_rs1a -At -f fr1_tickers.sql > a.tsv ; 같은 것을 rs1b로 ; diff
SELECT s.ticker,
       md5(string_agg(concat_ws('|', f.fiscal_year, f.fiscal_period, f.period_start, f.period_end, f.published_at,
                                f.revision_no, f.is_latest, f.report_code,
                                (to_jsonb(f) - 'id' - 'created_at' - 'stock_id')::text),
                      E'\n' ORDER BY f.fiscal_year, f.fiscal_period, f.published_at, f.revision_no)) AS sig
FROM financial_statement f JOIN stock s ON s.id = f.stock_id
WHERE s.market_code = 'US'
GROUP BY s.ticker
ORDER BY s.ticker;
