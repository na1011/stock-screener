-- 1부 손 FR 사후 대조 서명 v2(2026-10-06 · 1부 v11 계획 안 반증 7): FR이 쓰는 표 셋을 다 본다.
--   fs  — 종목마다 financial_statement 전 행(id · created_at · stock_id 제외 — fr1_tickers.sql과 같은 꼴)
--   fca — 종목마다 financial_column_absence 전 행(observed_at 제외 — GrossProfit · UnavailabilityRule이 읽고 되돌리기도 되돌린다)
--   sup — CIK마다 sec_filing_supplement 전 행(시각 칸 제외 · 옛 판 사용 표지는 있고 없음만)
-- 사용: psql -X -d <DB> -AtF $'\t' -v ids="$IDS" -v ciks="$CIKS" -f post_signature.sql > <DB>.sig.tsv — 두 DB의 출력을 diff한다.
SELECT 'fs' AS tbl, s.ticker AS key,
       md5(string_agg(concat_ws('|', f.fiscal_year, f.fiscal_period, f.period_start, f.period_end, f.published_at,
                                f.revision_no, f.is_latest, f.report_code,
                                (to_jsonb(f) - 'id' - 'created_at' - 'stock_id')::text),
                      E'\n' ORDER BY f.fiscal_year, f.fiscal_period, f.published_at, f.revision_no)) AS sig
FROM financial_statement f JOIN stock s ON s.id = f.stock_id
WHERE s.id IN (:ids)
GROUP BY s.ticker
UNION ALL
SELECT 'fca', s.ticker,
       md5(string_agg((to_jsonb(a) - 'observed_at' - 'stock_id')::text, E'\n' ORDER BY (to_jsonb(a) - 'observed_at' - 'stock_id')::text))
FROM financial_column_absence a JOIN stock s ON s.id = a.stock_id
WHERE s.id IN (:ids)
GROUP BY s.ticker
ORDER BY 1, 2;
