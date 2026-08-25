-- V16: 재무상태표의 세 항목을 더 받는다 — 유동자산·유동부채·이익잉여금
--
-- 이 셋은 **이미 오던 응답에 들어 있었다.** DART 주요계정(fnlttSinglAcnt)의 실측 응답이
-- `유동자산 / 비유동자산 / 자산총계`, `유동부채 / 비유동부채 / 부채총계`,
-- `자본금 / 이익잉여금 / 자본총계`를 함께 주는데(dart-financial.md의 「단일회사 주요계정」 절),
-- DartAccountMapper가 총계 셋만 뽑고 나머지를 버리고 있었다. 즉 호출 수도 응답 크기도
-- 늘지 않으며 쿼터에 아무 영향이 없다.
--
-- 이 셋을 받는 이유는 Altman Z-Score다. 다섯 항 중 운전자본(유동자산 − 유동부채)과
-- 이익잉여금이 여기서 나오고, 나머지 셋(EBIT·총자산·매출·총부채)과 시가총액은 이미 있다.
-- 개별 재무비율을 각각 통과하면서도 전체적으로 위험한 조합을 하나의 값으로 잡는 지표이며,
-- 집중 포트폴리오를 전제한 프리셋의 생존 게이트에 쓴다.
--
-- MUST NOT: 이 컬럼들로 비율을 저장하지 않는다. 원자료만 담는다는 성질은 스키마가 아니라
-- RawFinancialReport에 비율 필드가 없는 것으로 강제되며, 여기서도 같은 선을 지킨다.

ALTER TABLE financial_statement
    ADD COLUMN current_assets      numeric(24,2),
    ADD COLUMN current_liabilities numeric(24,2),
    ADD COLUMN retained_earnings   numeric(24,2);

COMMENT ON COLUMN financial_statement.current_assets IS
    '유동자산. 운전자본(유동자산 − 유동부채)의 재료다. 시점 잔액이라 Q4 유도에서 차감하지 않는다.';
COMMENT ON COLUMN financial_statement.current_liabilities IS
    '유동부채. 시점 잔액이라 Q4 유도에서 차감하지 않는다.';
COMMENT ON COLUMN financial_statement.retained_earnings IS
    '이익잉여금. Altman Z의 누적 수익성 항이다. 자본총계와 다르다 — 자본금·자본잉여금을 포함하지 않는다.';
