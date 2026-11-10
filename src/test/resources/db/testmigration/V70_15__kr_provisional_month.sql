-- ---------------------------------------------------------------------------
-- 실적 발표 본문 기간의 꼴에 MONTH를 더한다(ADR-0206 · V70_14의 짝)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — V70_14와 함께 국내 잠정 관문 직전에 주 경로로 한 글자도 안 바꾸고 옮긴다.
--
-- 실적 발표에서 빼는 것은 MONTH(기간이 한 달 안 · 월간 실적)뿐이다. OTHER는 회사가 기간을 잘못 적은 것이라(098460 2026-07-28 「2026-03-01 ~
-- 06-30」 · 머리 표지 26.2Q) 발표로 남겨 막고 잠정 행에는 안 쓴다 — 틀린 기간으로 발표를 빼면 막기가 풀려 옛 분기 값이 나간다.

ALTER TABLE kr_provisional_release DROP CONSTRAINT kr_provisional_release_release_period_check;
ALTER TABLE kr_provisional_release ADD CONSTRAINT kr_provisional_release_release_period_check
    CHECK (release_period IS NULL OR release_period IN ('QUARTER', 'YEAR', 'MONTH', 'OTHER'));
