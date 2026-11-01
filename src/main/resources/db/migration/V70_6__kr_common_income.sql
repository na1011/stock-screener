-- ---------------------------------------------------------------------------
-- 국내 보통주 몫(ADR-0192) — financial_common_income(V70_5)에 국내 열을 더한다
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 먼저 여는 관문(미국 · 국내)이 V70~V70_6 전부를 번호 순서대로 `src/main/resources/db/migration/`으로
-- 한 글자도 안 바꾸고 옮긴다(운영 Flyway는 out-of-order가 꺼져 있어 뒤 번호를 먼저 옮기면 앞 번호가 나중에 못 선다).
-- 운영 코드는 국내 관문(`stockscreener.gate.kr`)이 켜졌을 때만 국내 행을 읽고 쓴다.
--
-- 국내 행은 붙일 때의 행 스냅숏으로 낡음을 가른다 — 국내 report_code(11011 등)는 판을 안 가른다. 미국 행은 새 열이 전부 NULL이다.

ALTER TABLE financial_common_income ALTER COLUMN branch TYPE varchar(32);

ALTER TABLE financial_common_income
    ADD COLUMN statement_basis    char(1),
    ADD COLUMN payout_base        numeric(24,2),
    ADD COLUMN income_snapshot    numeric(24,2),
    ADD COLUMN published_snapshot date;

ALTER TABLE financial_common_income DROP CONSTRAINT ck_financial_common_income_value;
ALTER TABLE financial_common_income ADD CONSTRAINT ck_financial_common_income_value
    CHECK (common IS NULL OR (branch NOT IN ('ni', 'nokey') AND branch NOT LIKE 'none%'));
ALTER TABLE financial_common_income ADD CONSTRAINT ck_financial_common_income_statement
    CHECK (statement_basis IS NULL OR statement_basis IN ('C', 'S', 'N'));

COMMENT ON COLUMN financial_common_income.branch IS
    '미국: ni = 배분 0의 증인(값 대신 행 지배 NI) · none · nokey · q4-* = 못 정함. 국내(ADR-0192): kr-ni = 배분 0(값 = 지배 NI) · exw = 원장 보통주 EPS × 가중평균 · cum · q4 · none:* · nokey = 못 정함(그 창 미표시)';
COMMENT ON COLUMN financial_common_income.statement_basis IS
    '국내만 — 앵커가 정한 재료의 기저(C 연결 · S 별도 · N 못 정함). 미국은 NULL';
COMMENT ON COLUMN financial_common_income.payout_base IS
    '국내만 — 배당성향 분모(2026-10-25 사용자 결정 「지배 NI − 신종자본증권 몫」 · 중단 포함). NULL이면 그 해 배당성향 미표시. 미국은 NULL(분모는 보통주 몫)';
COMMENT ON COLUMN financial_common_income.income_snapshot IS
    '국내만 — 붙일 때의 coalesce(net_income_controlling, net_income). 읽는 쪽이 지금 행과 같을 때만 쓴다';
COMMENT ON COLUMN financial_common_income.published_snapshot IS
    '국내만 — 붙일 때의 published_at. 읽는 쪽이 지금 행과 같을 때만 쓴다';
