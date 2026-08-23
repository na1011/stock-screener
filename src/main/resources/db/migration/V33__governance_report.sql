-- 거버넌스 원자료를 원문 그대로 담고, 지표가 읽을 숫자를 따로 유도한다 (ADR-0081).
--
-- 표를 둘로 가르는 것이 이 phase의 핵심 결정이다. DART 정기보고서 주요정보 여덟은 응답 모양이
-- 여덟 다 다른데(항목×주식종류 행 · 주주별 행 · 사건별 행 · 평면 …) 지표가 보는 것은 전부
-- 「종목 × 회계연도 × 스칼라」다. 그래서 적재는 원문 한 표에, 지표용은 정규화된 한 표에 둔다.
--
-- 가르지 않으면 매핑을 한 번 틀릴 때마다 350종목을 다시 받아야 한다. 실제로 값 집합이
-- 개발가이드와 달랐던 자리가 넷이다(docs/api-spec/dart-open-api.md §16-C) — 배당 항목명,
-- 최대주주의 합계 행, 최대주주 변동의 누적 여부, 보수의 3개년 여부. 유도를 떼어 두면
-- 그런 정정이 재적재가 아니라 재유도(외부 호출 0)로 끝난다.
--
-- MUST NOT: financial_statement에 컬럼으로 얹지 않는다. 주기가 다르고(분기 대 연 스냅샷),
-- 행 축이 있고(주식종류·주주), 값이 금액이 아닌 것이 섞인다.

CREATE TABLE governance_report_row (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_id     bigint      NOT NULL REFERENCES stock(id),
    endpoint     varchar(24) NOT NULL,
    bsns_year    int         NOT NULL,
    reprt_code   varchar(8)  NOT NULL,
    receipt_no   varchar(20) NOT NULL,
    published_at date,
    row_ordinal  int         NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),

    -- receipt_no가 키에 들어가는 이유는 정정본 때문이다. 같은 (종목·엔드포인트·연도·보고서)에
    -- 접수번호가 다른 판이 쌓이고, 리더가 그중 가장 큰 것을 고른다 — financial_statement의
    -- revision_no와 같은 자리이며 여기서는 소스가 준 접수번호가 그 역할을 한다.
    --
    -- MUST: receipt_no를 NOT NULL로 둔다. status 013(데이터 없음)에는 rcept_no가 없어 NULL을
    -- 넣고 싶어지는데, PostgreSQL의 UNIQUE는 기본이 NULLS DISTINCT라 그러면 **제약이 아무것도
    -- 막지 않고 실행 횟수만큼 013 행이 쌓인다.** 그 수 위에 커버리지 판정이 서므로 조용히 부푼다.
    CONSTRAINT uq_gov_row UNIQUE (stock_id, endpoint, bsns_year, reprt_code, receipt_no, row_ordinal),

    -- 도메인 이름이다. DART 경로(alotMatter 등)는 어댑터 안에만 있다 — ADR-0006이 collector·
    -- batch 패키지의 코드에 벤더 이름이 등장하는 것을 MUST NOT으로 걸고, CHECK 문자열은
    -- 그 코드가 그대로 읽는 값이다.
    CONSTRAINT ck_gov_endpoint CHECK (endpoint IN
        ('SHARE_TOTALS', 'DIVIDEND', 'EXEC_PAY', 'OUTSIDE_DIRECTOR',
         'LARGEST_HOLDER', 'MINORITY_HOLDER', 'CONTROL_CHANGE', 'AUDIT_OPINION'))
);

COMMENT ON TABLE governance_report_row IS
    '정기보고서 주요정보의 응답 행을 원문 그대로 담는다(ADR-0081). 값은 소스가 준 문자열 '
    '그대로이고 파싱은 유도 단계가 한다 — 콤마가 섞이고 센티널이 빈 문자열이 아니라 ''-''라, '
    '타입 컬럼으로 받아도 파싱을 앞당길 수 없기 때문이다.';
COMMENT ON COLUMN governance_report_row.endpoint IS
    '도메인 이름. DART 경로가 아니다 — 매핑은 datasource/kr의 어댑터 안에만 있다(ADR-0006).';
COMMENT ON COLUMN governance_report_row.receipt_no IS
    '소스 rcept_no(14자리). 정정본을 가르는 유일한 키다. status 013 행은 빈 문자열이다 — '
    'NULL로 두면 uq_gov_row가 NULLS DISTINCT라 아무것도 막지 못한다.';
COMMENT ON COLUMN governance_report_row.published_at IS
    'rcept_no 앞 8자리에서 유도한 공시일(DartReportPeriods.publishedAt). status 013 행은 NULL이다 '
    '— 그 경로는 유도 함수를 부르지 않는다. 폴백(회계연도 말일)을 쓰면 없는 공시일을 지어내게 되고, '
    '지표의 계단 함수가 그 날짜를 「그날 알고 있었다」로 읽는다.';
COMMENT ON COLUMN governance_report_row.row_ordinal IS
    '응답 배열의 0부터의 순번. status 013은 -1이다 — 「미수집」과 「그 회사에 그 표가 없음」을 '
    '가르는 것이 이 행의 존재 이유다. 둘을 못 가르면 커버리지 결손의 원인을 업종·구조·수집으로 '
    '가를 수 없다.';
COMMENT ON COLUMN governance_report_row.payload IS
    '응답 행의 필드 전부. 키는 소스 필드명 그대로다. '
    'MUST NOT: qota_rt·shrholdr_rate·hold_stock_rate·jan_avrg_mendng_am을 지표 계산에 쓰지 않는다 '
    '— 소스가 이미 계산해 준 값이고 spec §7이 그것에 의존하는 것을 금지한다. 원자료로 담는 것까지가 '
    '범위이며 financial_statement의 eps·bps와 같은 취급이다.';

-- 유도된 「종목 × 회계연도 × 스칼라」. 지표 계층이 읽는 유일한 표다.
CREATE TABLE governance_fact (
    stock_id     bigint        NOT NULL REFERENCES stock(id),
    fiscal_year  int           NOT NULL,
    metric_key   varchar(40)   NOT NULL,
    value        numeric(24,8) NOT NULL,
    published_at date          NOT NULL,
    receipt_no   varchar(20)   NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (stock_id, fiscal_year, metric_key, receipt_no)
);

COMMENT ON TABLE governance_fact IS
    'governance_report_row에서 유도한 숫자 하나(ADR-0081). 외부 호출 없이 다시 만들 수 있다 — '
    '유도 규칙이 틀린 것을 알았을 때 재적재가 아니라 재유도로 끝나는 것이 표를 가른 이유다.';
COMMENT ON COLUMN governance_fact.metric_key IS
    'GovernanceMetric enum의 이름. CHECK를 걸지 않은 이유는 쓰기도 읽기도 그 enum 하나를 지나 '
    '리더가 다른 문자열로 물을 경로가 없기 때문이다(market_index_daily의 ck_market_index_code와 '
    '다른 점이 이것이다). 대신 리더는 모르는 키를 던지지 않고 건너뛰며, 유도 잡이 그 수를 '
    '실행당 한 번 집계해 알린다. MUST NOT: enum에서 값을 빼거나 이름을 바꾸지 않는다 — 컴파일도 '
    '테스트도 초록인 채 그 지표만 전 종목 판정불가가 되고, 화면은 등록된 결손 사유를 그대로 '
    '띄워 거짓 이유를 말한다.';
COMMENT ON COLUMN governance_fact.value IS
    '숫자만 담는다. 문자열에서 나오는 판정(강조사항 유무·감사인 교체)은 유도 단계에서 0/1로 '
    '바꾸고 원문은 governance_report_row에 남긴다.';
COMMENT ON COLUMN governance_fact.published_at IS
    '이 값을 실은 보고서의 공시일. 지표가 봉마다 「그날 공시돼 있던 회계연도」를 고르는 근거이며, '
    '이것이 없으면 이듬해 3월에 나온 값을 회계연도 말일에 알았던 것처럼 쓰게 된다.';
COMMENT ON COLUMN governance_fact.receipt_no IS
    '유도의 근거가 된 보고서의 접수번호. 리더가 같은 (종목·연도·지표)에서 가장 큰 것을 골라 '
    '정정본을 반영한다. 보고서 둘을 견줘 만드는 값(감사인 교체)은 **둘 중 큰 쪽**을 쓴다.';

-- 인덱스를 따로 만들지 않는다. 읽기는 종목 하나의 전 회계연도를 한 번에 가져가는 형태라
-- 두 표 모두 앞선 키의 접두사(stock_id …)로 끝난다. 종목당 행이 수십 개 규모라 스캔 폭도 작다.

-- CollectionPhase에 GOVERNANCE가 늘었다. V5의 ck_failure_phase가 실재하므로 함께 늘린다.
-- 이 단계는 종목 단위 실패라 batch_execution_failure에 실제로 쓰인다(UNIVERSE·MARKET_INDEX와
-- 다른 점이 그것이다).
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE'));
