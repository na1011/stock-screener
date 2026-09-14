-- 소스가 「그 컬럼의 부분 개념만 낸다」를 셋째 상태로 담는다.
--
-- 지금까지 ABSENT는 「후보 태그가 이름으로도 없다」였고, 그 안에 「우리 후보는 없지만
-- 부분 개념 태그는 있다」가 함께 들어 있었다. 그래서 화면이 D&A 제외 원가를 내는 회사에도
-- "이 회사는 매출원가·매출총이익을 공시에 내지 않습니다"라고 말했다 — EDGAR 전수 탐침으로
-- 그 거짓 문장을 받는 종목이 20이다.
--
-- MUST: 상태를 늘릴 때 이 CHECK를 함께 고친다. 안 고치면 적재가 제약 위반으로 죽는데,
-- 죽는 자리가 배치 한가운데라 무엇이 원인인지 로그에서 멀다.
ALTER TABLE financial_column_absence DROP CONSTRAINT ck_fca_state;
ALTER TABLE financial_column_absence
    ADD CONSTRAINT ck_fca_state CHECK (state IN ('ABSENT', 'FILTERED', 'PARTIAL_ONLY'));

COMMENT ON COLUMN financial_column_absence.state IS
    'ABSENT=후보 태그가 소스에 이름으로도 없다 · FILTERED=태그는 있는데 우리 필터가 다 걸렀다 '
    '· PARTIAL_ONLY=후보는 없고 부분 개념 태그만 있다. ABSENT와 PARTIAL_ONLY만 화면 사유가 된다';
