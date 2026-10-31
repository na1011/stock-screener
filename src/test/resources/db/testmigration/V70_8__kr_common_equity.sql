-- ---------------------------------------------------------------------------
-- 국내 보통주 자본 (주당 지표 분모 · ADR-0199)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 먼저 여는 관문(시장 무관)이 V70~V70_8 전부를 번호 순서대로 `src/main/resources/db/migration/`으로
-- 한 글자도 안 바꾸고 옮긴다(운영 Flyway out-of-order 꺼짐 — V71이 먼저 들어간 뒤에는 이 파일이 막힌다).
-- 운영 코드는 국내 관문(`stockscreener.gate.kr`)이 켜졌을 때만 이 열을 읽고 쓴다.
--
-- 국내 행은 non_common = ncav_non_common = 지배기업이 낸 신종자본증권(보통주보다 앞서는 고정 청구권)이고, 보통주와 나란한 잔여 청구권은
-- 보통주 몫(common_fraction = 보통주 유통 ÷ 전 종류 유통)으로 싣는다 — PBR 분모는 (지배 자본 − non_common) × common_fraction.
-- 미국 행은 NULL(= 1 · 우선주는 non_common이 장부가로 뺀다).

ALTER TABLE financial_common_income
    ADD COLUMN common_fraction numeric(20,18);

ALTER TABLE financial_common_income
    DROP CONSTRAINT ck_financial_common_income_equity;

ALTER TABLE financial_common_income
    ADD CONSTRAINT ck_financial_common_income_equity
        CHECK (equity_branch IS NOT NULL OR (non_common IS NULL AND ncav_non_common IS NULL AND common_fraction IS NULL));

COMMENT ON COLUMN financial_common_income.common_fraction IS
    '보통주와 나란한 잔여 청구권 중 보통주 몫(국내 · 보통주 유통 ÷ 전 종류 유통) — PBR 분모는 (지배 자본 − non_common) × 이 값. NULL = 1(미국).';
COMMENT ON COLUMN financial_common_income.equity_branch IS
    '판정 갈래 — 미국(CommonEquity) stmt · tag · sertag · liq · lp · carry · immat · nominal · zero · none · mezz · subsid · retired = 정함, open · divonly · liqconf · conv · nokey = 못 정함 · 국내(KrCommonEquity) kr · kr-s = 정함, kr-nokey · kr-open-h · kr-open-c = 못 정함. NULL = 이 규칙으로 안 읽음(관문 꺼짐).';
