-- V3: 통합 조건 모델
--
-- .claude/rules/backend.md가 요구하는 세 가지가 동시에 성립해야 한다.
--   1. 프리셋(가중 점수)과 커스텀 필터(충족/불충족)가 하나의 조건 모델을 공유한다.
--   2. 지금의 평면 AND 목록이 향후 OR 트리의 최상위 계층으로 흡수된다.
--   3. 피연산자가 리터럴 또는 다른 지표일 수 있다(합타입).
--
-- app_user는 V4에서 만들어지므로 owner_user_id의 FK는 V4에서 건다.
-- 컬럼은 여기 있고 제약만 뒤에 붙는다 — 안정성 기준 분할을 유지하기 위한 의도적 배치다.


-- ---------------------------------------------------------------------------
-- screen — 스크리닝 정의. 프리셋과 커스텀이 같은 테이블이다.
--
-- screen_type(누가 소유하는가)과 evaluation_mode(어떻게 판정하는가)를 분리한 것이 핵심이다.
-- 오늘은 상관관계가 있지만(프리셋은 점수, 커스텀은 필터) 본질적으로 직교한다.
-- 사용자가 자기 조건에 가중치를 주고 싶어하는 순간이 오고, 그때 컬럼을 추가하고
-- 백필하는 것보다 지금 두 컬럼으로 두는 것이 싸다.
-- spec §3은 상관관계를 서술한 것이지 제약을 규정한 것이 아니다.
-- ---------------------------------------------------------------------------
CREATE TABLE screen (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 프리셋만 non-null. 시딩·재시딩의 안정적 식별자다.
    code            varchar(60),
    name            varchar(100) NOT NULL,
    description     text,
    screen_type     varchar(20)  NOT NULL,
    evaluation_mode varchar(20)  NOT NULL,
    owner_user_id   bigint,
    -- NULL이면 전체 유니버스 대상
    universe_code   varchar(32) REFERENCES universe(code),
    is_active       boolean      NOT NULL DEFAULT true,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT ck_screen_type CHECK (screen_type IN ('PRESET','CUSTOM')),
    CONSTRAINT ck_screen_mode CHECK (evaluation_mode IN ('FILTER','SCORING')),
    -- 소유자 없는 커스텀 화면이나 소유자 있는 프리셋이 애초에 존재할 수 없게 한다.
    CONSTRAINT ck_screen_ownership CHECK (
        (screen_type = 'PRESET' AND owner_user_id IS NULL AND code IS NOT NULL)
     OR (screen_type = 'CUSTOM' AND owner_user_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_screen_code ON screen (code) WHERE code IS NOT NULL;
CREATE UNIQUE INDEX uq_screen_owner_name ON screen (owner_user_id, name) WHERE owner_user_id IS NOT NULL;


-- ---------------------------------------------------------------------------
-- condition_group — 조건 그룹. OR 트리 흡수의 실체.
--
-- 이 테이블은 구조적으로 이미 트리다(자기참조 FK + logical_operator).
-- v1의 "AND만" 제약은 스키마가 아니라 애플리케이션 계층에 있다 — v1은 화면당 그룹 하나를
-- parent_group_id IS NULL, logical_operator='AND'로만 만들고 API가 중첩 생성을 거부한다.
-- 깊이를 제한하는 스키마 제약을 일부러 넣지 않는다. 나중에 제거해야 할 것이기 때문이다.
--
-- 그래서 번역기는 처음부터 일반형으로 작성한다 — 그룹을 재귀하며 logical_operator에 따라
-- (EXISTS AND EXISTS) 또는 (EXISTS OR EXISTS)를 낸다. 그룹이 하나면 결과적으로 평면 AND가 나온다.
-- v2의 OR 기능은 순수한 UI 변경이 되고 백엔드는 손대지 않는다. 이것이 "흡수 가능"의 가장 강한 형태다.
-- ---------------------------------------------------------------------------
CREATE TABLE condition_group (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    screen_id        bigint      NOT NULL REFERENCES screen(id) ON DELETE CASCADE,
    parent_group_id  bigint REFERENCES condition_group(id) ON DELETE CASCADE,
    logical_operator varchar(8)  NOT NULL DEFAULT 'AND',
    sort_order       int         NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_group_operator CHECK (logical_operator IN ('AND','OR'))
);

CREATE UNIQUE INDEX uq_group_root ON condition_group (screen_id) WHERE parent_group_id IS NULL;
CREATE INDEX idx_group_screen ON condition_group (screen_id);
CREATE INDEX idx_group_parent ON condition_group (parent_group_id);


-- ---------------------------------------------------------------------------
-- screen_condition — 개별 조건
--
-- 합타입(리터럴 | 지표 참조)을 CHECK 제약으로 DB에서 강제한다. 서비스 계층 검증은
-- 모든 쓰기 경로가 기억해야 성립하지만 제약은 잊을 수 없다.
-- jsonb 한 컬럼으로 두는 대안은 indicator FK를 잃어 "이 지표를 비활성화하면 어떤 화면이
-- 깨지는가"를 물을 수 없게 되고, 조건 종류별 테이블 분리는 번역기에 UNION을 강요해
-- backend.md가 금지한 구조적 분기가 된다.
-- ---------------------------------------------------------------------------
CREATE TABLE screen_condition (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id               bigint      NOT NULL REFERENCES condition_group(id) ON DELETE CASCADE,
    -- 좌변은 항상 지표다.
    indicator_code         varchar(40) NOT NULL REFERENCES indicator(code),
    operator               varchar(20) NOT NULL,
    -- 합타입 판별자
    operand_type           varchar(20) NOT NULL,
    literal_value          numeric(24,8),
    literal_value_2        numeric(24,8),
    operand_indicator_code varchar(40) REFERENCES indicator(code),
    -- "종가 > SMA200 × 1.05" 같은 여유를 둔 돌파 조건을 위한 계수. NULL이면 1.0이다.
    -- 이것이 없으면 그런 조건마다 하드코딩 우회가 생긴다.
    operand_multiplier     numeric(12,6),
    -- SCORING에서만 쓴다. NOT NULL DEFAULT 1로 두지 않는 이유는 FILTER 조건이
    -- 점수에 참여하는 것처럼 보이면 안 되기 때문이다.
    weight                 numeric(6,3),
    sort_order             int         NOT NULL DEFAULT 0,
    created_at             timestamptz NOT NULL DEFAULT now(),

    -- 연산자는 닫힌 소형 집합이다. 번역기는 여기에만 분기하고 지표 코드에는 분기하지 않는다.
    CONSTRAINT ck_cond_operator CHECK (operator IN ('GT','GTE','LT','LTE','EQ','NEQ','BETWEEN')),
    CONSTRAINT ck_cond_operand_type CHECK (operand_type IN ('LITERAL','INDICATOR')),
    -- 합타입: 둘 중 정확히 하나만 채워진다.
    CONSTRAINT ck_cond_operand CHECK (
        (operand_type = 'LITERAL'   AND literal_value IS NOT NULL AND operand_indicator_code IS NULL)
     OR (operand_type = 'INDICATOR' AND operand_indicator_code IS NOT NULL AND literal_value IS NULL)
    ),
    CONSTRAINT ck_cond_between CHECK (
        (operator = 'BETWEEN'  AND literal_value_2 IS NOT NULL AND operand_type = 'LITERAL')
     OR (operator <> 'BETWEEN' AND literal_value_2 IS NULL)
    ),
    CONSTRAINT ck_cond_weight CHECK (weight IS NULL OR weight > 0)
);

CREATE INDEX idx_cond_group ON screen_condition (group_id);
-- "이 지표를 비활성화하면 어떤 저장된 화면이 영향받는가" — Phase 6 관리자 기능이 쓴다.
CREATE INDEX idx_cond_indicator ON screen_condition (indicator_code);


-- ---------------------------------------------------------------------------
-- watchlist_item — 관심종목
--
-- 알림 조건으로 screen을 재사용한다. backend.md의 "하나의 조건 모델"을 세 번째 소비자에
-- 그대로 적용한 것이다. 별도 조건 모델을 만들면 지표 추가 시 고칠 곳이 하나 더 생긴다.
-- ---------------------------------------------------------------------------
CREATE TABLE watchlist_item (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          bigint      NOT NULL,
    stock_id         bigint      NOT NULL REFERENCES stock(id),
    -- NULL이면 알림 없이 추적만 한다.
    screen_id        bigint REFERENCES screen(id),
    memo             text,
    -- 조건이 계속 참인 동안 매일 밤 다시 알리는 것을 막는다.
    -- 나중에 추가하려면 기능이 처음 도는 날 밤에 이미 스팸이 나간 뒤다.
    last_notified_at timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_watchlist_user_stock UNIQUE (user_id, stock_id)
);

CREATE INDEX idx_watchlist_alerting ON watchlist_item (screen_id) WHERE screen_id IS NOT NULL;
