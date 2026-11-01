-- ---------------------------------------------------------------------------
-- 보통주 귀속 이익 (주당 지표 분자 · 탐색 `explore/financial-sync/PERSHARE-NUM-PLAN-1024.md` v2.1)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — V70 · V70_1~4와 함께 미국 관문 직전에 `src/main/resources/db/migration/`으로 한 글자도 안 바꾸고
-- 옮긴다. 운영 코드는 미국 관문(`stockscreener.gate.us`)이 켜졌을 때만 이 표를 읽고 쓴다.
--
-- 재무 행(판 하나)마다 한 행 — 클래스 축 유도가 공시 × 기간의 값을 그 행의 판에 맞춰 붙인다(Q4는 어댑터가 쓴 세 분기 판으로).
-- 재무 행 id에 걸어 둔다 — 판이 지워지면 같이 지워지고, 종목 접기(StockFold)가 판을 옮기면 같이 옮겨진다. 다음 유도가 다시 붙인다.

CREATE TABLE financial_common_income (
    financial_statement_id bigint      NOT NULL,
    report_code            varchar(40) NOT NULL,
    common                 numeric(24,2),
    basis                  char(1)     NOT NULL,
    branch                 varchar(12) NOT NULL,
    engine_version         integer     NOT NULL,
    derived_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_financial_common_income PRIMARY KEY (financial_statement_id),
    CONSTRAINT fk_financial_common_income_statement FOREIGN KEY (financial_statement_id)
        REFERENCES financial_statement (id) ON DELETE CASCADE,
    CONSTRAINT ck_financial_common_income_basis CHECK (basis IN ('T', 'C')),
    CONSTRAINT ck_financial_common_income_value CHECK (common IS NULL OR branch NOT IN ('ni', 'none', 'nokey'))
);

COMMENT ON TABLE financial_common_income IS
    '재무 행마다 보통주 귀속 이익(주당 지표 분자). branch ni = 배분 0의 증인(값 대신 행 지배 NI) · none · nokey · q4-* = 못 정함(그 창 미표시).';
COMMENT ON COLUMN financial_common_income.report_code IS
    '붙일 때의 재무 행 report_code — 읽는 쪽이 지금 행과 같을 때만 쓴다(판이 바뀌었는데 유도가 아직 안 돈 행을 옛 값으로 안 읽게).';
COMMENT ON COLUMN financial_common_income.basis IS
    'T = 중단 포함 합(지표가 지배 중단을 뺀다) · C = 계속영업 EPS에서 지은 계속영업 몫(그대로 쓴다).';
