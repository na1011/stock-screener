-- V21: 기업 행위를 원장에 적는다 — 수정계수의 정본이 된다 (ADR-0049)
--
-- 지금까지 수정계수는 `adj_factor *= 1/ratio`로 **누적**됐고 그 곱셈에는 이력이 없었다.
-- 계수만 남고 이유가 없으니 되돌리는 수단이 DB 덤프뿐이었고, 시세 수집이 창으로 돌아 같은
-- 날짜를 평일 11~12회 다시 보는 이상(ADR-0042) 소스가 사건을 주기 시작하면 같은 분할이
-- 그만큼 반복 적용된다. 5:1을 15회 곱하면 numeric(18,10)이 정확히 0이 되고, 그러면
-- adj_close도 전 구간 0이 되는데 **아무 에러도 나지 않는다.**
--
-- 이 테이블이 있으면 계수를 누적하지 않고 매번 여기서 다시 지을 수 있다. 그때 되돌리기가
-- "행 하나를 REJECTED로 돌리고 다시 짓기"가 되고, 중복 적용은 원리적으로 사라진다.
--
-- 날짜가 셋인 것이 요점이다. 액면분할은 effective_date와 listing_date가 같지만 무상증자는
-- 14~16거래일 벌어진다(루닛 2023·2026 실측, docs/api-spec/dart-open-api.md §10-B). 한 컬럼에
-- 접으면 권리락~신주상장 구간의 상장주식수 조정을 표현할 수 없고, 소스 원값(source_date)과
-- 접으면 소스가 틀린 건지 우리 일자 보정이 틀린 건지 가를 수 없게 된다.

CREATE TABLE corporate_action (
    id               bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_id         bigint         NOT NULL REFERENCES stock(id),
    effective_date   date           NOT NULL,
    listing_date     date,
    source_date      date,
    type             varchar(16)    NOT NULL,
    ratio            numeric(18,10) NOT NULL,
    detection_source varchar(24)    NOT NULL,
    status           varchar(16)    NOT NULL,
    applied_at       timestamptz,
    note             text,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),

    -- MUST NOT: 키에 type을 넣지 않는다. 넣으면 같은 사건이 휴리스틱의 SPLIT과 DART의
    -- BONUS_ISSUE로 **두 행**이 되고, 둘 다 승인되면 재구축이 두 계수를 곱해 정확히 두 번
    -- 적용한다 — 에러 없이. 한 종목의 한 거래일에 우리가 구별할 수 있는 사건은 하나다.
    CONSTRAINT uq_ca UNIQUE (stock_id, effective_date),

    CONSTRAINT ck_ca_type   CHECK (type IN ('SPLIT', 'REVERSE_SPLIT', 'BONUS_ISSUE', 'UNKNOWN')),
    CONSTRAINT ck_ca_source CHECK (detection_source IN ('SOURCE_DISCLOSURE', 'HEURISTIC')),
    CONSTRAINT ck_ca_status CHECK (status IN
        ('OBSERVED', 'APPROVED', 'APPLIED', 'REJECTED', 'UNSUPPORTED')),

    -- IMPORTANT — 이 제약이 있어야 하는 이유가 실측으로 있다. detectCandidates가 병합에
    -- 역수(>1)를 실어 보내고 있었고, 적용부가 type을 읽지 않아 무증상이었다. 대한전선
    -- 2023-05-16을 그대로 승인하면 과거 가격이 10.63배 올라가야 할 자리에서 10.63배
    -- 내려간다 — 오차 113배인데 140원이라는 값 자체는 그럴듯하다.
    -- ratio의 뜻은 "1주가 몇 주가 되었는가"이므로 병합이면 1보다 작다.
    CONSTRAINT ck_ca_ratio_sign CHECK (
        ratio > 0 AND (
            (type = 'SPLIT'         AND ratio > 1) OR
            (type = 'REVERSE_SPLIT' AND ratio < 1) OR
            (type = 'BONUS_ISSUE'   AND ratio > 1) OR
             type = 'UNKNOWN')
    )
);

-- MUST NOT: listing_date >= effective_date를 CHECK로 걸지 않는다. 변경상장일과 거래재개일의
-- 선후를 잰 적이 없고(대한전선은 병합 전 11거래일이 비어 있다), 적용된 마이그레이션은
-- 고칠 수 없다. 재지 않은 관계를 제약으로 만들면 적재가 막히는 형태로 드러난다.
--
-- MUST NOT: applied_at을 status와 잇지 않는다. (status='APPLIED') = (applied_at IS NOT NULL)로
-- 묶으면 APPLIED를 REJECTED로 되돌릴 때 applied_at을 반드시 NULL로 만들어야 하고,
-- "한때 적용됐었다"는 사실이 사라진다. 되돌리기가 이 설계의 목적이므로 그것을 지우면 안 된다.

CREATE INDEX idx_ca_stock ON corporate_action (stock_id, effective_date);
CREATE INDEX idx_ca_status ON corporate_action (status);

COMMENT ON TABLE corporate_action IS
    '수정계수의 정본. adj_factor는 이 원장에서 매번 다시 계산되며 누적되지 않는다(ADR-0049).';
COMMENT ON COLUMN corporate_action.effective_date IS
    '권리락일 — 변경된 가격으로 거래가 시작되는 첫 거래일. 이 날 **이전** 봉이 조정 대상이다.';
COMMENT ON COLUMN corporate_action.listing_date IS
    '신주상장일 — 상장주식수가 늘어나는 날. 액면분할은 권리락과 같고 무상증자는 14~16거래일 뒤다.';
COMMENT ON COLUMN corporate_action.source_date IS
    '소스가 준 원값(DART isu_dcrs_de). 일자 보정 전이며, 보정이 틀렸을 때 원값과 대조하려고 남긴다.';
COMMENT ON COLUMN corporate_action.type IS
    'SPLIT=액면분할 · REVERSE_SPLIT=액면병합 · BONUS_ISSUE=무상증자 · UNKNOWN=가격이 뛰었으나 종류 미확정.';
COMMENT ON COLUMN corporate_action.ratio IS
    '1주가 몇 주가 되었는가. 5:1 분할이면 5, 10:1 병합이면 0.1, 1:1 무상증자면 2.';
COMMENT ON COLUMN corporate_action.detection_source IS
    'SOURCE_DISCLOSURE=공시가 알려줬다(신뢰 가능) · HEURISTIC=시세 급변으로 추정했다(사람 확인 필요).';
COMMENT ON COLUMN corporate_action.status IS
    'OBSERVED=후보 · APPROVED=적용 대상 · APPLIED=계수에 반영됨 · REJECTED=오탐 · UNSUPPORTED=원리적으로 못 고침(인적분할 등).';
