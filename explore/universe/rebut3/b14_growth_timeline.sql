-- 글자대로의 F6(V1) 집합이 편입이 그대로 유지될 때 언제 커지나: 현 편입마다 「마지막 앞선 행의 effective_to」
-- = 창 하한 lo가 그 날짜에 닿는 순간(약 1년 뒤) 대상이 된다. 월별 누적(시장별)
WITH cur AS (
  SELECT m.id, m.stock_id, m.effective_from, s.market_code
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active
), lp AS (
  SELECT c.market_code, c.effective_from,
    (SELECT max(m.effective_to) FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
      WHERE m.stock_id=c.stock_id AND m.id<>c.id AND m.effective_from < c.effective_from) last_prior_to
  FROM cur c
)
SELECT market_code,
  count(*) FILTER (WHERE last_prior_to IS NULL) now_target,
  count(*) FILTER (WHERE last_prior_to < '2026-03-01') by_2027_03,
  count(*) FILTER (WHERE last_prior_to < '2026-08-01') by_2027_08_start,
  count(*) FILTER (WHERE last_prior_to <= '2026-08-05') kr_cliff_by_2027_08_06,
  count(*) FILTER (WHERE last_prior_to <= '2026-08-31') us_cliff_by_2027_09_01,
  count(*) FILTER (WHERE last_prior_to IS NOT NULL AND effective_from <= last_prior_to + 0) adjacent_rows,
  count(*) total
FROM lp GROUP BY 1 ORDER BY 1;
