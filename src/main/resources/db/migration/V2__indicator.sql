-- V2: 지표 카탈로그와 지표 값(EAV)
--
-- spec §7의 "스키마 변경 없이 새 지표를 추가할 수 있어야 한다"를 구현하는 계층이다.
-- 새 지표 = indicator에 행 하나 + indicator_value에 행들. 컬럼도 테이블도 늘지 않는다.
-- Phase 3에서 재작업 가능성이 가장 높아 V1(비싼 원자료)과 분리했다.


-- ---------------------------------------------------------------------------
-- indicator — 지표 카탈로그
--
-- 코드 자체가 PK다. 조건·계산기 레지스트리·API가 모두 이 코드로 지표를 가리키고,
-- 개발자가 직접 짓는 안정적인 식별자이며, 저장된 화면을 raw SQL로 들여다볼 때 읽을 수 있다.
-- ---------------------------------------------------------------------------
CREATE TABLE indicator (
    code            varchar(40) PRIMARY KEY,
    name            varchar(100) NOT NULL,
    -- spec §5: 지표가 무엇을 뜻하는지. 초보 사용자가 값만 보고는 알 수 없다.
    description     text,
    -- spec §5: 어느 방향이 유리한 신호인지. description과 분리한 이유는 화면에서 다르게 렌더링하기 때문이다.
    interpretation  text,
    category        varchar(20)  NOT NULL,
    value_type      varchar(20)  NOT NULL,
    unit            varchar(20),
    decimal_scale   smallint     NOT NULL DEFAULT 2,
    -- NULL은 "방향이 정해지지 않음"이다(RSI처럼 중간이 좋은 지표).
    -- 점수 계산에서 백분위가 어느 쪽이 좋은 것인지 판단하는 근거다.
    higher_is_better boolean,
    is_active       boolean      NOT NULL DEFAULT true,
    sort_order      int          NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_indicator_category CHECK (category IN ('TECHNICAL','FUNDAMENTAL','PRICE','DERIVED')),
    CONSTRAINT ck_indicator_value_type CHECK (value_type IN ('RATIO','PERCENT','CURRENCY','COUNT','PRICE'))
);

COMMENT ON COLUMN indicator.is_active IS
    '폐기된 지표는 삭제하지 않고 false로 둔다. 저장된 커스텀 화면이 참조하고 있으면 삭제 시 화면이 깨진다.';
COMMENT ON COLUMN indicator.value_type IS
    'CURRENCY·PRICE는 통화가 달라 시장 간 직접 비교가 불가능하다. 그런 지표는 백분위(DERIVED)로 비교한다.';


-- ---------------------------------------------------------------------------
-- indicator_value — 지표 값 (EAV)
--
-- PK가 (trade_date, indicator_code, stock_id) 순인 것이 이 스키마에서 가장 중요한 결정이다.
-- 스크리닝은 항상 "최신 일자 하나를 고정하고, 지표별로 값 범위를 필터"하므로
-- trade_date와 indicator_code가 모든 쿼리에서 등치 조건이다. 이 순서로 두면 하루치가
-- 물리적으로 뭉쳐 있고 조건 하나가 850개 연속 인덱스 엔트리로 좁혀진다.
-- stock_id를 앞에 두면 그 850개가 인덱스 전체에 흩어진다.
-- 야간 대량 upsert가 인덱스 오른쪽 끝에 append되는 것도 같은 순서 덕분이다.
--
-- 실측(7,650,000행 = 850종목 × 150지표 × 60일, 조건 3개 AND):
--   실행 0.185ms, idx_iv_screen에 Index Only Scan.
-- ---------------------------------------------------------------------------
CREATE TABLE indicator_value (
    stock_id       bigint        NOT NULL REFERENCES stock(id),
    trade_date     date          NOT NULL,
    indicator_code varchar(40)   NOT NULL REFERENCES indicator(code),
    -- 모든 지표가 단위와 무관하게 이 한 컬럼에 들어간다.
    -- numeric인 이유: 부동소수점이면 "PER이 정확히 10.00인데 왜 PER <= 10에 안 걸리지"가 생긴다.
    value          numeric(24,8) NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (trade_date, indicator_code, stock_id)
);

COMMENT ON COLUMN indicator_value.value IS
    'MUST: 계산할 수 없으면 행을 쓰지 않는다. NULL 행을 만들지 않는다. 행이 없으면 EXISTS가 자연히 거짓이 되어 이력 부족 종목이 올바르게 제외된다.';

-- 스크리닝의 주 접근 경로. value까지 인덱스에 있어 범위 술어가 인덱스 안에서 끝나고,
-- stock_id는 INCLUDE 페이로드에서 나와 힙을 건드리지 않는다(Index Only Scan).
CREATE INDEX idx_iv_screen ON indicator_value (trade_date, indicator_code, value) INCLUDE (stock_id);

-- 역방향 접근 패턴: 종목 상세 화면("이 종목의 오늘 지표 전부")과
-- Phase 6 워치리스트 판정(소수 종목 × 전체 지표). 이 인덱스가 없으면 경로가 없다.
CREATE INDEX idx_iv_stock_date ON indicator_value (stock_id, trade_date);
