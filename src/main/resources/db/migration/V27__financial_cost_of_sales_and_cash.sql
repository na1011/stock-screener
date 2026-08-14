-- 매출원가와 현금및현금성자산.
--
-- 새 엔드포인트가 아니다. 이미 쓰고 있는 fnlttSinglAcntAll(전체 재무제표) 응답 안에 들어
-- 있는 계정과목이며, 매퍼가 읽지 않고 있었을 뿐이다(ADR-0062).
--
-- 기존 행은 NULL로 남는다 — V16의 current_assets가 그랬던 것과 같고, 채우는 것은
-- full-statement-refill 재적재의 몫이다.
--
-- MUST NOT: 매출원가가 비어 있는 것을 곧바로 결손으로 읽지 않는다. 금융업·서비스업은
-- 매출원가 개념 자체가 없어 원래 오지 않는다(실측: 8개사 중 KB금융·NAVER 둘).
ALTER TABLE financial_statement
    ADD COLUMN cost_of_sales numeric(24, 2),
    ADD COLUMN cash_and_equivalents numeric(24, 2);

COMMENT ON COLUMN financial_statement.cost_of_sales IS
    '매출원가. 매출총이익 = revenue - cost_of_sales. 업종에 따라 원래 없을 수 있다.';
COMMENT ON COLUMN financial_statement.cash_and_equivalents IS
    '현금및현금성자산(재무상태표). 현금흐름표의 기초/기말 잔액과 다른 값이므로 sj_div=BS에서만 읽는다.';
