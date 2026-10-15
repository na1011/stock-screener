-- ---------------------------------------------------------------------------
-- 주식수 클래스 축 (미국 관문 · 탐색 `SHARES-CLASS-PLAN-V5-1015.md` §2·§10 · ADR 예정)
-- ---------------------------------------------------------------------------
-- MUST: 이 파일은 관문 마이그레이션이다 — 관문 직전에 `src/main/resources/db/migration/`으로 **내용을 한 글자도 안 바꾸고**
-- 옮긴다(체크섬이 같아야 이미 적용된 시험 DB가 그대로 선다). 그때까지 시험 프로파일만 이 표를 만든다(`db/testmigration` 위치).
-- 운영 코드는 `stockscreener.gate.us`가 켜졌을 때만 이 표를 부른다 — 꺼진 jar는 이름으로도 안 부른다(CLAUDE.md 「재적재·백필」:
-- 마이그레이션은 설정 뒤에 못 둔다).
-- 번호가 운영의 다음 번호와 겹치면 시험이 「같은 버전 둘」로 멈춘다 — 그때 이 파일의 번호를 올린다(적용된 시험 DB는 새로 만든다).
--
-- 쓰기 가드: V66의 이벤트 트리거가 세 표에 가드를 단다(보호 제외 목록에 안 넣는다 — 앱 JVM 안에서만 쓴다).

-- ① 종목(상장 줄) 하나의 companyfacts에서 판정이 쓰는 축 없는 값 — 받은 그대로(고르기 전)의 가지친 트리.
--    유도기가 외부 호출 없이 판정을 다시 돌리는 재료다(분할 원장이 늦게 와도 다시 받지 않고 다시 판정한다).
--    줄마다 한 행인 이유: 판정이 상장 줄에 매여 있다(클래스 매핑 · 줄 단위 N_L). 같은 CIK의 형제 줄은 같은 트리를 따로 든다.
CREATE TABLE sec_share_class_input (
    stock_id      bigint      NOT NULL,
    cik           bigint      NOT NULL,
    company_facts jsonb       NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_sec_share_class_input PRIMARY KEY (stock_id),
    CONSTRAINT fk_sec_share_class_input_stock FOREIGN KEY (stock_id) REFERENCES stock (id)
);
CREATE INDEX ix_sec_share_class_input_cik ON sec_share_class_input (cik);
COMMENT ON TABLE sec_share_class_input IS
    'companyfacts 중 클래스 축 판정이 쓰는 축 없는 값(분모·EPS·분자·표지 · 공시 목록) — 종목마다 마지막으로 받은 것. 손으로 고치지 않는다';

-- ② 클래스 축 앵커 후보 공시(2020-09 뒤 정기 공시 중 companyfacts에 축 없는 표지 주식수가 없는 것)의 인스턴스에서 읽은 팩트.
--    공시는 한 번 나오면 안 바뀌므로 평생 한 번 받는다. 다시 받는 것은 FAILED 중 UNREADABLE이 아닌 것과 reader_version이 지금과
--    다른 행이다. facts는 읽기 규칙(축의 뜻으로 받기 · ClassFactReader)을 지난 팩트다 — 규칙을 바꾸면 판을 올린다.
CREATE TABLE sec_filing_class_fact (
    cik             bigint       NOT NULL,
    accession       varchar(20)  NOT NULL,
    form            varchar(10)  NOT NULL,
    filed           date         NOT NULL,
    reader_version  integer      NOT NULL,
    status          varchar(8)   NOT NULL,
    facts           jsonb,
    failure_kind    varchar(20),
    failure_message text,
    fetched_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sec_filing_class_fact PRIMARY KEY (cik, accession),
    CONSTRAINT ck_sec_filing_class_fact_status CHECK (status IN ('OK', 'NO_XBRL', 'FAILED')),
    CONSTRAINT ck_sec_filing_class_fact_shape CHECK (
        (status = 'OK' AND facts IS NOT NULL AND failure_kind IS NULL)
        OR (status = 'NO_XBRL' AND facts IS NULL AND failure_kind IS NULL)
        OR (status = 'FAILED' AND facts IS NULL AND failure_kind IS NOT NULL))
);
COMMENT ON TABLE sec_filing_class_fact IS
    '클래스 축 앵커 후보 공시의 인스턴스 팩트(클래스 축 맥락 포함) — (CIK, 접수번호)마다 평생 한 행. 손으로 고치지 않는다';

-- ③ 판정 — 상장 줄 × 정기 공시. 유도기(ShareClassDeriver)가 ①·②·분할 원장에서 다시 만든다(외부 0).
--    키에 줄을 넣는 이유: 같은 공시라도 줄마다 N_L 단위가 다르고(BRK.A/BRK.B · GEF/GEF.B), 매핑이 줄에 따라 갈린다
--    (UHAL 의결권 줄은 구성원에 못 붙어 숨김 · UHAL.B는 값 — 형제 22쌍 중 상태가 갈린 것은 그 4판뿐, 2026-10-15 스윕).
--    kind: T = 클래스 축 앵커(인스턴스로 판정) · d = 축 없는 표지(지금 경로 · 보임).
--    status: O 보임 · X 숨김. 숨김은 null이 아니라 상태다 — 판독기가 옛 판으로 물러나지 않는다.
--    failure: LEDGER(원장 어긋남 — 발행사 제외를 센다) · PROXY(대용의 한계) · ABSENT(원장이 안 줌).
--    current_path: 보이되 값은 지금 경로(재무 행의 주식수)가 낸다 — d와, 표지가 클래스 구성원에 없는 T(앵커 아님).
--    line_shares: 보인 판의 N_L. 지금 경로 판은 축 없는 표지(보류 편입의 마지막 보인 값 — 없으면 null) ·
--    denominator: 그 공시의 분모 n(10-K는 Q4 · 비 지표 셋의 두 끝).
CREATE TABLE financial_share_class_filing (
    stock_id        bigint        NOT NULL,
    accession       varchar(20)   NOT NULL,
    cik             bigint        NOT NULL,
    form            varchar(10)   NOT NULL,
    filed           date          NOT NULL,
    kind            char(1)       NOT NULL,
    status          char(1)       NOT NULL,
    branch          varchar(40)   NOT NULL,
    current_path    boolean       NOT NULL,
    failure         varchar(8),
    line_shares     numeric,
    denominator     numeric,
    engine_version  integer       NOT NULL,
    derived_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_financial_share_class_filing PRIMARY KEY (stock_id, accession),
    CONSTRAINT fk_financial_share_class_filing_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT ck_financial_share_class_filing_kind CHECK (kind IN ('T', 'd')),
    CONSTRAINT ck_financial_share_class_filing_status CHECK (status IN ('O', 'X')),
    CONSTRAINT ck_financial_share_class_filing_failure CHECK (failure IS NULL OR failure IN ('LEDGER', 'PROXY', 'ABSENT')),
    CONSTRAINT ck_financial_share_class_filing_current CHECK (NOT current_path OR status = 'O'),
    CONSTRAINT ck_financial_share_class_filing_shape CHECK (
        (status = 'O' AND failure IS NULL AND (line_shares IS NULL OR line_shares > 0))
        OR (status = 'X' AND line_shares IS NULL))
);
CREATE INDEX ix_financial_share_class_filing_filed ON financial_share_class_filing (stock_id, filed);
COMMENT ON TABLE financial_share_class_filing IS
    '주식수 클래스 축 판정 — 상장 줄 × 정기 공시. 그날까지 공시된 판정의 최신이 그 줄의 주식수를 정한다. 유도기만 쓴다';

-- ④ 재무 행 × 판정 = 판독기가 읽는 주식수 — 펜스(전제 SQL · 순위 가드)가 쓰는 정의 한 자리. 판독기는 자바의 ShareClassLine이
--    같은 규칙을 쓴다(통합 시험이 두 정의를 행마다 맞댄다). 규칙:
--    자기 공시의 판정이 있으면 그것(숨김 X · 지금 경로면 행 값 · 아니면 N_L).
--    없고 정기 공시(10-K·10-Q·20-F·40-F)면 — 그 줄이 그날까지 앵커 판정을 하나라도 가졌고 2020-09-01 뒤면 미판정 = 숨김,
--    아니면 지금 경로. 정기 공시가 아니면(정정 등) 그날 서 있던 판정을 따르고 N_L은 그 사이 분할만큼 옮긴다.
--    분모 n은 보인 판정의 것만.
CREATE VIEW us_share_class_effective AS
SELECT f.id AS financial_statement_id, f.stock_id, f.published_at,
       CASE WHEN v.status = 'X' OR unjudged.hidden THEN NULL
            WHEN v.status = 'O' AND NOT v.current_path THEN v.line_shares * coalesce((
                SELECT exp(sum(ln(ca.ratio))) FROM corporate_action ca
                WHERE ca.stock_id = f.stock_id AND ca.status = 'APPLIED'
                  AND ca.effective_date > v.filed AND ca.effective_date <= f.published_at), 1)
            ELSE f.shares_outstanding END AS shares,
       (coalesce(v.status = 'X', false) OR coalesce(unjudged.hidden, false)) AS hidden,
       CASE WHEN v.status = 'O' THEN v.denominator END AS denominator
FROM financial_statement f
JOIN stock s ON s.id = f.stock_id AND s.market_code = 'US'
LEFT JOIN financial_share_class_filing own
       ON own.stock_id = f.stock_id AND own.accession = split_part(f.report_code, ':', 2)
LEFT JOIN LATERAL (
    SELECT st.* FROM financial_share_class_filing st
    WHERE own.stock_id IS NULL
      AND split_part(f.report_code, ':', 1) NOT IN ('10-K', '10-Q', '20-F', '40-F')
      AND st.stock_id = f.stock_id AND st.filed <= f.published_at
    ORDER BY st.filed DESC, st.accession DESC
    LIMIT 1) standing ON true
LEFT JOIN LATERAL (
    SELECT true AS hidden
    WHERE own.stock_id IS NULL
      AND split_part(f.report_code, ':', 1) IN ('10-K', '10-Q', '20-F', '40-F')
      AND f.published_at >= DATE '2020-09-01'
      AND EXISTS (SELECT 1 FROM financial_share_class_filing t
                  WHERE t.stock_id = f.stock_id AND t.kind = 'T' AND t.filed <= f.published_at)) unjudged ON true
CROSS JOIN LATERAL (
    SELECT coalesce(own.status, standing.status) AS status,
           coalesce(own.current_path, standing.current_path) AS current_path,
           coalesce(own.line_shares, standing.line_shares) AS line_shares,
           coalesce(own.denominator, standing.denominator) AS denominator,
           coalesce(own.filed, standing.filed) AS filed) v;
COMMENT ON VIEW us_share_class_effective IS
    '미국 재무 행마다 클래스 축 판정을 입힌 주식수·숨김·분모 — 펜스가 쓰는 정의. 판독기의 ShareClassLine과 같은 규칙';
