-- F6 집합이 시간에 따라 어떻게 자라나: 복원 평면을 「관측」처럼 보면 1년 동안 처음 든 종목 수
-- (활성 유니버스 · 종목별 첫 편입 행 시작일 > 그 시장 복원 격자 첫날)
WITH first_row AS (
  SELECT s.market_code, m.stock_id, min(m.effective_from) first_from,
         bool_or(m.effective_to IS NULL) is_current
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE s.is_active
  GROUP BY 1,2
), grid_start AS (
  SELECT s.market_code, min(m.effective_from) g0
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  GROUP BY 1
)
SELECT f.market_code, g.g0,
  count(*) total_ever,
  count(*) FILTER (WHERE f.first_from > g.g0) first_entered_after_grid_start,
  count(*) FILTER (WHERE f.first_from > g.g0 AND f.is_current) of_which_current,
  count(*) FILTER (WHERE f.is_current) current_total
FROM first_row f JOIN grid_start g USING (market_code)
GROUP BY 1,2 ORDER BY 1;
