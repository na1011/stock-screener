-- 절벽: 술어를 글자대로(「창과 겹치는 앞선 편입 행이 없다」) 짜면, 관측 하한에서 갈린 RESTORED 행이 창 밖으로
-- 밀려나는 날 연속 편입 전원이 대상이 된다. 현 편입 중 「앞선 행이 전부 effective_to <= X」인 수를 X별로 센다.
WITH cur AS (
  SELECT m.id, m.stock_id, m.effective_from, s.market_code
  FROM universe_membership m JOIN stock s ON s.id=m.stock_id
  JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL AND s.is_active
),
last_prior AS (
  SELECT c.id, c.market_code, c.effective_from cur_from,
    (SELECT max(m.effective_to) FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
      WHERE m.stock_id=c.stock_id AND m.id<>c.id AND m.effective_from < c.effective_from) last_prior_to
  FROM cur c
)
SELECT market_code, cur_from, last_prior_to, count(*)
FROM last_prior
GROUP BY 1,2,3 ORDER BY 1, 4 DESC LIMIT 40;
