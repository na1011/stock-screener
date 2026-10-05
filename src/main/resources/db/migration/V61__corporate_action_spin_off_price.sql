-- 분사가 가격 배수(price_ratio)를 들고 원장에 들어오게 한다(분사 설계 2단계 — 탐색 브랜치 explore/spin-off/PLAN.md).
--
-- 1) SPIN_OFF의 ratio는 존속 주식 배수 s다. 미국 단순 분사는 1이고, 같은 날 액면분할과 합친 국내 사건은 1보다 크다
--    (SK텔레콤 2021-11-29 3.04). V48의 「SPIN_OFF는 1보다 작다」는 국내 단순 인적분할만 담던 때의 범위라 걷는다.
ALTER TABLE corporate_action DROP CONSTRAINT ck_ca_ratio_sign;
ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_ratio_sign
    CHECK (
        ratio > 0 AND (
            (type = 'SPLIT'         AND ratio > 1) OR
            (type = 'REVERSE_SPLIT' AND ratio < 1) OR
            (type = 'BONUS_ISSUE'   AND ratio > 1) OR
             type IN ('SPIN_OFF', 'UNKNOWN'))
    );

-- 2) 가격 배수가 주식수 배수와 갈리는 종류는 분사뿐이다. 분할에 다른 가격 배수가 실리면 주식수와 가격이
--    서로 다른 사건을 보고, 어느 쪽도 에러를 내지 않는다.
ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_price_ratio_scope
    CHECK (price_ratio IS NULL OR type = 'SPIN_OFF');

-- 3) 승인된 분사는 가격 배수가 있어야 한다. 없으면 재구축이 coalesce로 주식수 배수를 가격에 실어 —
--    미국 분사(ratio 1)는 무보정이 되고 ADR-0055 억제도 풀려 그럴듯한 틀린 값이 나간다.
--    NOT VALID: 이 규칙 전에 들어온 국내 APPLIED 2행(000880 · 012450)은 판정 API로 값을 넣을 때까지 남는다.
--    새로 쓰거나 고치는 행에는 지금부터 걸린다.
ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_spin_off_priced
    CHECK (type <> 'SPIN_OFF' OR status NOT IN ('APPROVED', 'APPLIED') OR price_ratio IS NOT NULL) NOT VALID;

-- 4) 가격 배수를 다시 낼 수 있는 원자료. 원장 행 하나에 하나다.
CREATE TABLE corporate_action_distribution (
    corporate_action_id bigint PRIMARY KEY REFERENCES corporate_action (id) ON DELETE CASCADE,
    new_symbol          varchar(32),
    new_cusip           varchar(16),
    rate                numeric(20, 10),
    value_date          date NOT NULL,
    parent_value        numeric(24, 4),
    child_value         numeric(24, 4),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cad_values_paired CHECK ((parent_value IS NULL) = (child_value IS NULL)),
    CONSTRAINT ck_cad_values_range CHECK (parent_value IS NULL OR (parent_value > 0 AND child_value >= 0))
);

COMMENT ON TABLE corporate_action_distribution IS
    '분사가 나눠 준 것 — 가격 배수 s/v를 다시 낼 원자료. v = parent_value / (parent_value + child_value)는 같은 날'
    '(value_date) 종가로 잰 존속 쪽 몫이다(미국: s × 모체 종가 · rate × 자식 종가 · 국내: 재상장일 존속·신설 보통주 시총). '
    '두 가치가 비었으면 아직 못 쟀다는 뜻이고 그 원장 행은 UNKNOWN·OBSERVED다';
