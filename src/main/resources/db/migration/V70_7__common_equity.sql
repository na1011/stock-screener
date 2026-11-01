-- ---------------------------------------------------------------------------
-- 보통주 자본 (주당 지표 분모 · 탐색 `explore/financial-sync/PERSHARE-DEN-PLAN-1026.md` v1.1)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — 먼저 여는 관문(시장 무관)이 V70~V70_7 전부를 번호 순서대로 `src/main/resources/db/migration/`으로
-- 한 글자도 안 바꾸고 옮긴다(운영 Flyway out-of-order 꺼짐 — 국내 관문이 먼저 열려 V71이 들어간 뒤에는 이 파일이 막힌다).
-- 운영 코드는 미국 관문(`stockscreener.gate.us`)이 켜졌을 때만 이 열을 읽고 쓴다.
--
-- 보통주 귀속 이익과 같은 행(재무 행 하나 · 같은 report_code 맞춤)에 싣는다 — 클래스 축 유도가 한 트랜잭션에 함께 갈아 넣는다.
-- 국내 행은 이 열을 안 쓴다(NULL = 이 규칙으로 안 읽음).

ALTER TABLE financial_common_income
    ADD COLUMN non_common      numeric(24,2),
    ADD COLUMN ncav_non_common numeric(24,2),
    ADD COLUMN equity_branch   varchar(12);

ALTER TABLE financial_common_income
    ADD CONSTRAINT ck_financial_common_income_equity
        CHECK (equity_branch IS NOT NULL OR (non_common IS NULL AND ncav_non_common IS NULL));

COMMENT ON COLUMN financial_common_income.non_common IS
    '보통주가 아닌 청구권(우선주 장부가 · GP 자본 · 우선 단위 · 보통 아닌 LP 클래스) — PBR 분모는 지배 자본 − 이 값. NULL이고 equity_branch가 있으면 못 정함(미표시).';
COMMENT ON COLUMN financial_common_income.ncav_non_common IS
    'NCAV가 유동자산에서 더 빼는 청구권 — 영구자본 우선주 · 우선 단위 · 모회사 메자닌 우선주(GP 자본은 보통주보다 앞서지 않아 뺀다).';
COMMENT ON COLUMN financial_common_income.equity_branch IS
    '판정 갈래(CommonEquity) — stmt · tag · sertag · liq · lp · carry · immat · nominal · zero · none · mezz · subsid · retired = 정함, open · divonly · liqconf · conv · nokey = 못 정함. NULL = 이 규칙으로 안 읽음(국내 · 관문 꺼짐).';
