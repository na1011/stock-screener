-- R0 격자(03-03,03-05,08-31,…)의 인접 점 사이 제외 비율 — UniverseLedgerRestorer.diff가 >30%(제외≥5)에서 유니버스째 중단한다.
-- 근사: 원장(RESTORED ∪ OBSERVED)의 그날 편입. 재생 명부는 소스·현재 원장으로 다시 만들므로 근사다.
WITH d(dt) AS (VALUES ('2026-03-03'::date),('2026-03-05'),('2026-08-31'),('2026-09-04'),('2026-09-07'),('2026-09-11'),('2026-09-18'),('2026-09-25'),('2026-10-02')),
mem AS (SELECT d.dt, m.stock_id FROM d JOIN universe_membership m ON m.universe_code='US_TOP1000'
          AND m.effective_from <= d.dt AND (m.effective_to IS NULL OR m.effective_to > d.dt)),
cnt AS (SELECT dt, count(*) n FROM mem GROUP BY dt),
pairs AS (SELECT dt, lag(dt) OVER (ORDER BY dt) prev FROM d)
SELECT p.prev, p.dt, (SELECT n FROM cnt WHERE cnt.dt=p.prev) before_n,
  (SELECT count(*) FROM mem a WHERE a.dt=p.prev AND NOT EXISTS (SELECT 1 FROM mem b WHERE b.dt=p.dt AND b.stock_id=a.stock_id)) removed
FROM pairs p WHERE p.prev IS NOT NULL ORDER BY p.dt;
