-- 거버넌스 원문의 F6 꼴: 현 편입 중 「열린 행 시작 > 마지막 전량 governance-load(#632 09-20 15:18)」인 종목의
-- 원문 최신 적재 시각·최신 사업연도를 같은 시장 현 편입과 비교한다.
SET statement_timeout='60s';
WITH cur AS (
  SELECT s.id, s.ticker, s.market_code, max(m.effective_from) start
  FROM universe_membership m JOIN stock s ON s.id = m.stock_id JOIN universe u ON u.code = m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active GROUP BY 1,2,3),
g AS (SELECT stock_id, max(created_at) last_load, max(bsns_year) FILTER (WHERE reprt_code IN ('11011','FY','10-K') OR true) maxyr, count(*) n
      FROM governance_report_row GROUP BY 1)
SELECT c.market_code, (c.start > date '2026-09-20') new_since_full, count(*) members,
       count(*) FILTER (WHERE g.stock_id IS NULL) no_rows,
       count(*) FILTER (WHERE g.last_load < timestamptz '2026-09-20 15:18+09') loaded_before_full,
       string_agg(c.ticker||'('||c.start||' '||coalesce(to_char(g.last_load AT TIME ZONE 'Asia/Seoul','MM-DD'),'-')||' '||coalesce(g.maxyr::text,'-')||')', ' ' ORDER BY c.ticker)
         FILTER (WHERE c.start > date '2026-09-20')
FROM cur c LEFT JOIN g ON g.stock_id = c.id
GROUP BY 1,2 ORDER BY 1,2;
