-- ---------------------------------------------------------------------------
-- 국내 봉의 거래소 기준가 · 시장 구분 (재고 「기준가 축」 · ADR-0205)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 먼저 여는 관문(시장 무관)이 V70~V70_13 전부를 번호 순서대로 `src/main/resources/db/migration/`으로
-- 한 글자도 안 바꾸고 옮긴다(운영 Flyway out-of-order 꺼짐 — V71이 먼저 들어간 뒤에는 이 파일이 막힌다).
--
-- 거래소가 기준가를 조정한 날(기준가 ≠ 직전 거래일 종가)은 그 자체로 기업행위 공시다 — 날짜와 가격 배수(직전 종가 ÷ 기준가)를 원장에
-- 적는다(KrBasePriceEvents). 계수는 저장하지 않는다 — 원장이 정본이고 재구축이 짓는다.

-- 공공데이터포털 getStockPriceInfo의 clpr − vs. vs = 0이면 소스가 기준가를 말하지 않은 날일 수 있다(같은 값이 종가와 같다) —
-- 그날은 주식수 계단이 확인할 때만 사건이다. 미국 봉은 NULL.
ALTER TABLE price_daily
    ADD COLUMN base_price numeric(18,4),
    ADD COLUMN market_category varchar(8);

COMMENT ON COLUMN price_daily.base_price IS
    '그 봉의 거래소 기준가(clpr − vs · 국내만) — 직전 거래일 종가와 다르면 거래소가 가격을 조정한 날이다.';
COMMENT ON COLUMN price_daily.market_category IS
    '그 봉의 시장 구분(mrktCtg · KOSPI/KOSDAQ/KONEX · 국내만) — 직전 봉과 다르면 이전상장일이라 기준가 차이가 조정이 아니다.';
