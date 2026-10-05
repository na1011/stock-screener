-- 리밸런싱 회차별 OBSERVED 편입 수(유니버스별) — 꼬리 상한 50·200과 비교
SELECT m.universe_code, m.effective_from, count(*) added,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM universe_membership m2 WHERE m2.stock_id=m.stock_id AND m2.universe_code=m.universe_code AND m2.effective_from < m.effective_from)) rejoin,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM financial_statement f WHERE f.stock_id=m.stock_id)) had_fin
FROM universe_membership m
WHERE m.origin='OBSERVED' AND m.effective_from > (SELECT min(effective_from) FROM universe_membership x WHERE x.universe_code=m.universe_code AND x.origin='OBSERVED')
GROUP BY 1,2 ORDER BY 2,1;
