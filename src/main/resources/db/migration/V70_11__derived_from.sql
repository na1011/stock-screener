-- ---------------------------------------------------------------------------
-- 저장 FY 판에서 지은 4분기 판의 원천 (재고 「유도 Q4 가시성」 남은 것 · KrEarlierVintages.restoreFourthQuarters)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 먼저 여는 관문(시장 무관)이 V70~V70_11 전부를 번호 순서대로 `src/main/resources/db/migration/`으로
-- 한 글자도 안 바꾸고 옮긴다(운영 Flyway out-of-order 꺼짐 — V71이 먼저 들어간 뒤에는 이 파일이 막힌다).
--
-- 옛 회수가 지운 국내 4분기 판은 DART가 옛 FY를 다시 안 줘 어댑터로 안 되살아난다 — 국내 관문 켬 잡이 저장 FY 판 − 그날 보이던 1~3분기로
-- 짓고, 매 실행 그 식으로 다시 맞춘다(FY 판 · 분기가 관문 재적재로 바뀌어도 따라간다). 원천 FY 판이 회수되면 이 판도 간다.
-- 원본 접수일 판의 원천(copied_from)과 따로 두는 이유: 그쪽은 같은 라벨의 값 복사, 이쪽은 다른 라벨(FY)에서의 유도라 다시 맞추는 식이 다르다.

ALTER TABLE financial_statement
    ADD COLUMN derived_from bigint REFERENCES financial_statement (id) ON DELETE CASCADE;

CREATE INDEX ix_financial_statement_derived_from ON financial_statement (derived_from) WHERE derived_from IS NOT NULL;

COMMENT ON COLUMN financial_statement.derived_from IS
    '저장 FY 판에서 지은 4분기 판(KrEarlierVintages.restoreFourthQuarters)의 원천 FY 판 id — 국내 관문 켬 잡이 매 실행 다시 맞춘다. NULL = 소스가 준 판.';
