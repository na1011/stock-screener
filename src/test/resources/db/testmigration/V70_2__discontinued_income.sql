-- ---------------------------------------------------------------------------
-- 중단영업 손익 (F2b · ADR-0184 · 탐색 `explore/universe/F2B-PLAN-1023.md`)
-- ---------------------------------------------------------------------------
-- MUST: 이 파일은 관문 마이그레이션이다 — 두 시장 관문 중 먼저 여는 쪽의 직전에 `src/main/resources/db/migration/`으로 내용을
-- 한 글자도 안 바꾸고 옮긴다(V70 · V70_1과 같은 이유로 따로 둔다). 운영 코드는 그 행 시장의 관문(`stockscreener.gate.us` ·
-- `stockscreener.gate.kr`)이 켜졌을 때만 두 컬럼을 읽고 쓴다.
--
-- 기존 `net_income` · `net_income_controlling`의 뜻은 안 바꾼다 — 원장 `eps`(중단 포함 기본 EPS)와 맞대는 소비자가 셋이다
-- (배당 주식수 20% 게이트 · 순위 오염 훅 · ADR-0126 축척 판정).

ALTER TABLE financial_statement ADD COLUMN discontinued_income numeric(24,2);
ALTER TABLE financial_statement ADD COLUMN discontinued_income_controlling numeric(24,2);

COMMENT ON COLUMN financial_statement.discontinued_income IS
    '중단영업 손익(연결 · 세후 · 비지배 몫 포함). 0 = 읽었고 중단영업 줄이 없다 · NULL = 이 규칙으로 읽지 않았다.';
COMMENT ON COLUMN financial_statement.discontinued_income_controlling IS
    '중단영업 손익의 지배주주 몫. 연결 몫이 0이 아닌데 NULL이면 원장이 지배 몫을 정해 주지 않았다(지배 계속영업 미표시).';
