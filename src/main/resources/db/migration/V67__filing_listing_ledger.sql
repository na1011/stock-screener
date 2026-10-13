-- ---------------------------------------------------------------------------
-- 원장 공시 목록과 소비 장부 (ADR-0176 · 재무 동기 2부)
-- ---------------------------------------------------------------------------
-- 새 정기 공시가 들어오는 길이 FR(목)·FSR(수, 국내)·새 편입 꼬리뿐이라, 원장에 있는 공시가 DB에 오기까지
-- 미국 약 15평일 · 국내 약 9평일이 걸렸다(P9 · 3분기 모형). 매 평일 원장의 그날 공시 목록을 받아 공시마다 한 행을
-- 장부에 쌓고, 소비 단계가 그 행을 지금 편입 종목의 좁힌 FR/FSR로 먹인다.
--
-- 1차는 목록과 장부 쌓기뿐이다 — 소비 칸(fr_consumed_at 등)은 소비 단계(2차)만 쓴다. 이 두 표는 값을 바꾸는 표를
-- 읽지도 쓰지도 않는다.
--
-- 쓰기 가드: V66의 이벤트 트리거가 두 표에 가드를 단다(보호 제외 목록에 안 넣는다 — 크론 토큰 안에서만 쓴다).

-- ① 날마다 「그날 목록을 받아 닫았다」. 시장마다 최대 날짜가 다음 실행의 커서다.
--    닫는 것은 그날의 목록을 끝까지 받은 뒤뿐이다 — 받다 실패한 날은 행이 없고 그 뒤 날도 닫지 않는다(커서가 안 넘어간다).
--    outcome: LISTED(받았다 · 행 0일 수 있다) · NO_INDEX(SEC가 그날 색인을 안 냈다 — 주말·연방 공휴일. 분기 목록의 마지막
--    날보다 앞인데 목록에 없는 날) · EMPTY(DART가 그날 013 — 공시 없음).
CREATE TABLE filing_listing_day (
    market        varchar(8)  NOT NULL,
    listing_date  date        NOT NULL,
    outcome       varchar(12) NOT NULL,
    source_rows   integer     NOT NULL,
    kept_rows     integer     NOT NULL,
    fetched_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_filing_listing_day PRIMARY KEY (market, listing_date),
    CONSTRAINT ck_filing_listing_day_market CHECK (market IN ('US', 'KR')),
    CONSTRAINT ck_filing_listing_day_outcome CHECK (outcome IN ('LISTED', 'NO_INDEX', 'EMPTY')),
    CONSTRAINT ck_filing_listing_day_rows CHECK (source_rows >= 0 AND kept_rows >= 0 AND kept_rows <= source_rows)
);
COMMENT ON TABLE filing_listing_day IS
    '원장 공시 목록을 날마다 끝까지 받아 닫은 기록(ADR-0176). 시장마다 최대 listing_date가 커서다. 손으로 고치지 않는다';
COMMENT ON COLUMN filing_listing_day.listing_date IS
    '원장의 공시일 — 미국은 EDGAR 일일 색인의 날짜(미 동부), 국내는 DART rcept_dt';
COMMENT ON COLUMN filing_listing_day.source_rows IS '거르기 전 정기 공시 줄 수(미국: PERIODIC_FORMS · 국내: 세 보고서 종류)';
COMMENT ON COLUMN filing_listing_day.kept_rows IS '우리 stock 표의 종목에 붙어 장부로 간 줄 수(종목이 여럿인 CIK는 종목마다 센다)';

-- ② 공시 하나 × 종목 하나 = 한 행. 키가 (시장, 접수번호, 종목)인 이유: 미국은 CIK 하나에 상장 종목이 여럿일 수 있고
--    (보통주 두 종류), 소비는 종목마다 FR을 돈다.
--    편입 여부는 여기서 거르지 않는다 — 재편입 종목의 공시를 잃지 않기 위해서다(소비 시점에 편입으로 고른다).
CREATE TABLE filing_consumption (
    id                    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market                varchar(8)  NOT NULL,
    filing_no             varchar(32) NOT NULL,
    stock_id              bigint      NOT NULL REFERENCES stock (id),
    cik                   bigint,
    corp_code             varchar(8),
    form                  varchar(32) NOT NULL,
    report_name           varchar(200),
    filed_on              date        NOT NULL,
    bsns_year             smallint,
    reprt_code            varchar(5),
    first_seen_at         timestamptz NOT NULL DEFAULT now(),
    attempts              integer     NOT NULL DEFAULT 0,
    last_attempt_at       timestamptz,
    fr_consumed_at        timestamptz,
    fsr_consumed_at       timestamptz,
    consumed_by_execution bigint,
    last_reason           text,
    CONSTRAINT uq_filing_consumption UNIQUE (market, filing_no, stock_id),
    CONSTRAINT ck_filing_consumption_market CHECK (market IN ('US', 'KR')),
    CONSTRAINT ck_filing_consumption_source CHECK (
        (market = 'US' AND cik IS NOT NULL AND corp_code IS NULL AND fsr_consumed_at IS NULL
                       AND bsns_year IS NULL AND reprt_code IS NULL)
        OR (market = 'KR' AND cik IS NULL AND corp_code IS NOT NULL)),
    CONSTRAINT ck_filing_consumption_attempts CHECK (attempts >= 0)
);
-- 소비 단계가 「소비 안 끝난 행」을 고른다 — 국내는 FR·FSR 두 칸, 미국은 FR 한 칸이다.
CREATE INDEX ix_filing_consumption_open ON filing_consumption (market, stock_id)
    WHERE fr_consumed_at IS NULL OR (market = 'KR' AND fsr_consumed_at IS NULL);
COMMENT ON TABLE filing_consumption IS
    '원장 정기 공시 × 우리 종목의 소비 장부(ADR-0176). 상태는 소비 단계만 바꾼다 — 손으로 고치지 않는다';
COMMENT ON COLUMN filing_consumption.filing_no IS '미국 accession(0001234567-26-000001) · 국내 rcept_no(14자리)';
COMMENT ON COLUMN filing_consumption.form IS '미국 폼(10-Q 등 · PERIODIC_FORMS) · 국내 공시 상세유형(A001 사업 · A002 반기 · A003 분기)';
COMMENT ON COLUMN filing_consumption.report_name IS '국내 report_nm 원문(「[기재정정]반기보고서 (2026.06)」) — 미국은 NULL';
COMMENT ON COLUMN filing_consumption.bsns_year IS
    '국내 탐침의 사업연도 — 보고서 이름 「(yyyy.mm)」의 연도. 읽지 못하면 NULL이고 그 사유가 last_reason에 있다';
COMMENT ON COLUMN filing_consumption.reprt_code IS
    '국내 탐침의 보고서 코드 — A001 11011 · A002 11012 · A003은 (보고서 월 − 결산월) mod 12가 3이면 11013, 9면 11014';
COMMENT ON COLUMN filing_consumption.fsr_consumed_at IS '국내 전체 재무제표(FSR) 몫 — 미국은 늘 NULL';

-- ③ 단계 집계 이름이 늘었다(CollectionPhase.FILING_LISTING). 목록 잡은 종목 단위 실패를 안 쓰지만 제약은 enum과 같게 둔다 —
--    CollectionPhaseConstraintTest가 둘을 대조한다.
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'PRICE_GAP', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR', 'FILING_LISTING'));
