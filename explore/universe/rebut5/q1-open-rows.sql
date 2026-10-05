SET statement_timeout='60s';
-- 활성 유니버스의 열린 행: 유니버스·origin·effective_from·created_at(KST) 분포
SELECT m.universe_code, m.origin, m.effective_from, to_char(min(m.created_at AT TIME ZONE 'Asia/Seoul'),'MM-DD HH24:MI') cmin,
       to_char(max(m.created_at AT TIME ZONE 'Asia/Seoul'),'MM-DD HH24:MI') cmax, count(*) n,
       to_char(m.effective_from,'Dy') dow
FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
JOIN stock s ON s.id=m.stock_id AND s.is_active
WHERE m.effective_to IS NULL GROUP BY 1,2,3 ORDER BY 1,3;
-- 활성 유니버스 둘 이상에 열린 행을 가진 종목
SELECT count(*) multi FROM (SELECT m.stock_id FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL GROUP BY 1 HAVING count(*)>1) x;
-- 인접 행: 열린 행의 시작일에 끝나는 같은 유니버스의 닫힌 행(연속 편입인데 시작이 밀린 경우)
SELECT m.universe_code, m.origin, p.origin prev_origin, count(*) adjacent, min(m.effective_from), max(m.effective_from)
FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
JOIN universe_membership p ON p.universe_code=m.universe_code AND p.stock_id=m.stock_id AND p.effective_to=m.effective_from
WHERE m.effective_to IS NULL GROUP BY 1,2,3;
