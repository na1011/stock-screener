-- B-2 「현 편입 행」을 어느 모집단으로 읽나 — 갈래별 수(초기값 NULL이면 전원 대상이 되는 크기)
-- (1) 열린 행 전체(유니버스 활성 무관) · (2) 활성 유니버스만 · (3) (2) ∧ 활성 종목 ∧ 수집 시장 = FR resolveTargets 모집단
SELECT u.code, u.is_active, count(*) open_rows, count(DISTINCT m.stock_id) stocks,
       count(*) FILTER (WHERE NOT s.is_active) inactive_stock,
       count(*) FILTER (WHERE s.market_code NOT IN ('KOSPI','KOSDAQ','US')) uncollected_mkt
FROM universe_membership m JOIN universe u ON u.code=m.universe_code JOIN stock s ON s.id=m.stock_id
WHERE m.effective_to IS NULL GROUP BY 1,2 ORDER BY 1;
-- 비활성 유니버스에만 열린 행이 있는 종목(활성 유니버스 열린 행 없음) — 술어가 유니버스 활성을 안 보면 NULL 초기값으로 전원 대상
SELECT count(DISTINCT m.stock_id) only_inactive_open
FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND NOT u.is_active
WHERE m.effective_to IS NULL
  AND NOT EXISTS (SELECT 1 FROM universe_membership m2 JOIN universe u2 ON u2.code=m2.universe_code AND u2.is_active
                  WHERE m2.stock_id=m.stock_id AND m2.effective_to IS NULL);
-- 한 종목이 활성 유니버스 열린 행을 둘 이상 가진 경우(「현 편입 행의 시작」이 둘)
SELECT count(*) multi_open_active FROM (
  SELECT m.stock_id FROM universe_membership m JOIN universe u ON u.code=m.universe_code AND u.is_active
  WHERE m.effective_to IS NULL GROUP BY 1 HAVING count(*)>1) x;
-- 미국 현 편입 1,000 중 US_LISTED 열린 행도 가진 것과 그 시작일
SELECT count(*) us_top_with_listed_open, min(l.effective_from), max(l.effective_from)
FROM universe_membership t JOIN universe_membership l ON l.stock_id=t.stock_id AND l.universe_code='US_LISTED' AND l.effective_to IS NULL
WHERE t.universe_code='US_TOP1000' AND t.effective_to IS NULL;
