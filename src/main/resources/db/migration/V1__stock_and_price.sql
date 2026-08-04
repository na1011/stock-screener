-- V1: 시장·종목·유니버스·시세·재무제표
--
-- 안정성이 가장 높은 계층이다. 형태를 외부 데이터 소스가 결정하므로 설계 판단 여지가 적고,
-- Phase 2에서 850종목 × 2년치 시세와 3년치 재무제표를 적재하면 재작성 비용이 가장 커진다.

-- universe_membership의 기간 중첩 배제 제약에 필요하다.
CREATE EXTENSION IF NOT EXISTS btree_gist;


-- ---------------------------------------------------------------------------
-- market — 상장 시장(거래 장소). 지수(universe)와는 다른 개념이다.
-- 종목은 정확히 하나의 시장에 상장되지만(안정적 속성), 지수 편입은 시간에 따라 변한다(관계).
-- 이 둘을 합치면 첫 리밸런싱에서 재작성해야 한다.
-- ---------------------------------------------------------------------------
CREATE TABLE market (
    code          varchar(16) PRIMARY KEY,
    country_code  char(2)     NOT NULL,
    currency_code char(3)     NOT NULL,
    timezone      varchar(64) NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE market IS '상장 시장. 통화·시간대의 근거이며 3행 규모라 자연키를 PK로 쓴다.';

INSERT INTO market (code, country_code, currency_code, timezone) VALUES
    ('KOSPI',  'KR', 'KRW', 'Asia/Seoul'),
    ('KOSDAQ', 'KR', 'KRW', 'Asia/Seoul'),
    ('US',     'US', 'USD', 'America/New_York');


-- ---------------------------------------------------------------------------
-- stock — 종목
-- ---------------------------------------------------------------------------
CREATE TABLE stock (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_code  varchar(16)  NOT NULL REFERENCES market(code),
    -- MUST: 티커는 문자열이다. 한국 티커 '005930'을 정수로 다루면 5930이 된다.
    ticker       varchar(20)  NOT NULL,
    name         varchar(200) NOT NULL,
    name_en      varchar(200),
    sector       varchar(100),
    listed_on    date,
    delisted_on  date,
    is_active    boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    -- 티커 단독으로는 유일하지 않다. 미국 티커는 상장폐지 후 재사용되고(FB→META가 FB를 풀어줌),
    -- 새 시장이 추가되면 즉시 충돌한다. 다른 테이블은 반드시 id로 참조한다.
    CONSTRAINT uq_stock_market_ticker UNIQUE (market_code, ticker)
);

CREATE INDEX idx_stock_active ON stock (is_active) WHERE is_active;

COMMENT ON COLUMN stock.delisted_on IS '상장폐지일. 행을 지우지 않는 이유는 과거 시세·지표가 참조하기 때문이다.';


-- ---------------------------------------------------------------------------
-- universe — 지수(코스피200·코스닥150·S&P500)
-- ---------------------------------------------------------------------------
CREATE TABLE universe (
    code        varchar(32) PRIMARY KEY,
    name        varchar(100) NOT NULL,
    market_code varchar(16)  NOT NULL REFERENCES market(code),
    is_active   boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

INSERT INTO universe (code, name, market_code) VALUES
    ('KOSPI200',  '코스피200',  'KOSPI'),
    ('KOSDAQ150', '코스닥150',  'KOSDAQ'),
    ('SP500',     'S&P 500',    'US');


-- ---------------------------------------------------------------------------
-- universe_membership — 지수 편입 이력 (시간 구간)
--
-- 편입 여부를 stock의 컬럼으로 두지 않는 이유가 셋 있고, 각각 단독으로도 충분하다.
--   1. 리밸런싱 배치가 속성을 덮어쓰면 "무엇이 바뀌었는지"가 사라진다. 그것이 관리자 화면과
--      알림이 필요로 하는 정보다.
--   2. 종목은 편입→제외→재편입된다. 불리언 속성으로는 표현할 수 없다.
--   3. 관리자 기능(spec §4)이 이력을 조회한다.
--
-- 구간은 [effective_from, effective_to) 반열림이며 NULL은 현재 편입 중을 뜻한다.
-- 9999-12-31 같은 파수꾼 값 대신 NULL을 쓰는 이유는 부분 인덱스를 만들 수 있기 때문이다.
-- ---------------------------------------------------------------------------
CREATE TABLE universe_membership (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    universe_code  varchar(32) NOT NULL REFERENCES universe(code),
    stock_id       bigint      NOT NULL REFERENCES stock(id),
    effective_from date        NOT NULL,
    effective_to   date,
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_membership_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    -- 같은 날 리밸런싱 배치를 두 번 돌려도 중복 삽입되지 않는다.
    CONSTRAINT uq_membership UNIQUE (universe_code, stock_id, effective_from),
    -- 한 종목이 같은 지수에 겹치는 구간으로 두 번 편입될 수 없다.
    -- 이것이 없으면 배치 버그가 스크리닝 결과의 행을 조용히 부풀리는데, 테스트에서 거의 보이지 않는다.
    CONSTRAINT ex_membership_no_overlap EXCLUDE USING gist (
        universe_code WITH =,
        stock_id WITH =,
        daterange(effective_from, effective_to) WITH &&
    )
);

-- 모든 스크리닝 쿼리가 "현재 이 지수에 편입된 종목"을 필터한다. 850행 규모라 항상 캐시에 있다.
CREATE INDEX idx_membership_current ON universe_membership (universe_code, stock_id)
    WHERE effective_to IS NULL;
CREATE INDEX idx_membership_stock ON universe_membership (stock_id);


-- ---------------------------------------------------------------------------
-- price_daily — 일별 시세
--
-- IMPORTANT — 수정주가(adj_close)와 원주가(close)를 함께 저장한다.
-- 액면분할 시 원주가로 계산한 SMA200은 무의미해지고, 분할 당일 거짓 데드크로스를 만든다.
-- 삼성전자 50:1 분할 기준으로 계산하면 SMA200이 실제의 50배로 왜곡된다.
--   - 기술적 지표는 예외 없이 adj_close로 계산한다.
--   - PER·PBR·시가총액·화면 표시가는 close(원주가)로 계산한다. 사용자가 실제 지불하는 가격이고
--     시점의 발행주식수와 짝이 맞아야 하기 때문이다.
-- adj_factor를 명시적으로 저장하는 이유는 새 분할이 공시됐을 때 과거 전체를 재계산했는지
-- 확인할 수 있어야 하기 때문이다. 소스가 조용히 수정본을 내주는 것에 의존하지 않는다.
-- ---------------------------------------------------------------------------
CREATE TABLE price_daily (
    stock_id    bigint        NOT NULL REFERENCES stock(id),
    trade_date  date          NOT NULL,
    open        numeric(18,4) NOT NULL,
    high        numeric(18,4) NOT NULL,
    low         numeric(18,4) NOT NULL,
    close       numeric(18,4) NOT NULL,
    adj_close   numeric(18,6) NOT NULL,
    adj_factor  numeric(18,10) NOT NULL DEFAULT 1,
    -- 코스닥 소형주는 하루 수억 주가 거래된다. integer(21억) 상한에 걸릴 수 있어 bigint로 둔다.
    volume      bigint        NOT NULL,
    -- 거래대금. KRX는 제공하고 Finnhub는 제공하지 않는다.
    -- MUST NOT: close × volume으로 합성해 채우지 않는다. 다른 값이며, 실측과 합성이 섞이면
    -- 파생 지표가 시장마다 미묘하게 달라진다. 없으면 NULL로 두고 지표를 계산하지 않는다.
    trade_value numeric(20,2),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),

    -- 대리키를 두지 않는다. 접근 패턴이 종목별 기간 스캔이므로 이 PK 자체가 접근 경로다.
    PRIMARY KEY (stock_id, trade_date),

    -- 무료 API의 손상된 데이터를 삽입 시점에 잡는다. 통과하면 ATR·지지저항이 조용히 망가진다.
    CONSTRAINT ck_price_ohlc CHECK (
        high >= low AND high >= open AND high >= close AND low <= open AND low <= close
    ),
    CONSTRAINT ck_price_volume CHECK (volume >= 0)
);

CREATE INDEX idx_price_date ON price_daily (trade_date);


-- ---------------------------------------------------------------------------
-- financial_statement — 재무제표 (연간·분기)
--
-- 정정공시(DART 정정)는 덮어쓰지 않고 새 행으로 쌓는다. published_at을 처음부터 기록하는 이유는,
-- 나중에 "그 시점에 공개되어 있던 값"이 필요해져도 데이터가 남아 있어야 하기 때문이다.
-- v1은 그 질의를 구현하지 않는다(항상 최신본을 쓴다) — 질의는 미루되 데이터는 미루지 않는다.
-- 하나의 가변 행으로 두면 정보가 영구히 파괴되어 어떤 마이그레이션으로도 복구할 수 없다.
-- ---------------------------------------------------------------------------
CREATE TABLE financial_statement (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_id            bigint      NOT NULL REFERENCES stock(id),
    fiscal_year         int         NOT NULL,
    fiscal_period       varchar(4)  NOT NULL,
    -- 회계연도는 달력연도와 다를 수 있다(애플 FY2025는 2025-09-27 종료).
    -- 기간의 근거는 이 두 컬럼이며 fiscal_year는 라벨일 뿐이다.
    period_start        date        NOT NULL,
    period_end          date        NOT NULL,
    published_at        date        NOT NULL,
    revision_no         int         NOT NULL DEFAULT 0,
    is_latest           boolean     NOT NULL DEFAULT true,
    report_code         varchar(40),
    -- 시세와 달리 재무제표는 상장 시장과 다른 통화로 보고될 수 있어 행 단위 사실이다.
    currency_code       char(3)     NOT NULL,

    revenue             numeric(24,2),
    operating_income    numeric(24,2),
    net_income          numeric(24,2),
    total_assets        numeric(24,2),
    total_equity        numeric(24,2),
    total_liabilities   numeric(24,2),
    operating_cash_flow numeric(24,2),
    -- 미국 공시는 가중평균 희석주식수를 소수로 보고한다. bigint로 절사하면 EPS·PER에
    -- 작지만 체계적인 편향이 생긴다.
    shares_outstanding  numeric(20,2),
    eps                 numeric(18,6),
    bps                 numeric(18,6),
    created_at          timestamptz NOT NULL DEFAULT now(),

    -- FY와 Q4를 모두 허용한다. DART는 사업보고서와 분기보고서를 따로 내고 Q4는 종종
    -- FY-(Q1+Q2+Q3)로만 유도되므로, 소스가 준 형태 그대로 저장하고 판단은 지표 계층에 맡긴다.
    CONSTRAINT ck_fs_period CHECK (fiscal_period IN ('FY','Q1','Q2','Q3','Q4')),
    CONSTRAINT ck_fs_range CHECK (period_end > period_start),
    CONSTRAINT uq_fs_revision UNIQUE (stock_id, fiscal_year, fiscal_period, revision_no)
);

-- 기간별 현재 유효본이 정확히 하나임을 보장한다. v1의 "최신본을 쓴다"가 모호해지지 않는다.
CREATE UNIQUE INDEX uq_fs_latest ON financial_statement (stock_id, fiscal_year, fiscal_period)
    WHERE is_latest;
-- TTM 합산(최근 4개 분기)과 향후 as-of 질의의 접근 경로.
CREATE INDEX idx_fs_asof ON financial_statement (stock_id, period_end DESC, published_at DESC);
