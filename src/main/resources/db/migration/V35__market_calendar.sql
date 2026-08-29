-- V35: 거래소 세션 달력 (ADR-0091)
--
-- 봉이 없는 날을 휴장·무거래·비활성·거래정지로 가를 근거. 이 표가 닫는 것은 **휴장 하나**이고
-- 나머지 셋은 여전히 뭉쳐 있다 — 거래정지 목록이 없고 stock.is_active·delisted_on·listed_on이
-- 미국 전 종목에서 비어 있다. 그 한계는 docs/api-spec/alpaca-calendar.md가 적는다.
--
-- MUST NOT: grain을 시장(market_code)으로 잡지 않는다. 조인 키를 stock.listing_exchange로
-- 두는 것이 이 설계의 요점이다 — 국내 종목은 그 값이 NULL이라 이 표와 **원리적으로 조인되지
-- 않는다.** 「국내를 캘린더로 판정하지 마라」가 규칙이 아니라 구조가 된다. 시장으로 잡으면
-- 그 보호가 사라지고, 캘린더 소스가 없는 국내가 「전부 휴장」으로 읽힐 자리가 생긴다.
--
-- MUST NOT: market에 NYSE·NASDAQ 행을 만들지 않는다(ADR-0086이 기각했다 — 유니버스가 둘로
-- 갈리고 백분위 모집단이 상장 장소로 갈린다). 이 표는 market을 참조하지 않는다.

CREATE TABLE market_calendar (
    exchange_code varchar(16) NOT NULL,

    -- 시장 로컬 날짜. MUST: 소스가 준 date를 그대로 쓴다.
    -- MUST NOT: core_start에서 유도하지 않는다 — timestamptz는 절대 시각이라 UTC로 환산하면
    -- 뉴욕 오후 장이 다음 날로 밀린다. 그 오류는 하루치 판정을 통째로 뒤집는다.
    session_date  date        NOT NULL,

    -- 정규장 시각. 소스의 필드명(core_start·core_end)을 그대로 쓴다 — 이름을 바꾸면 실측
    -- 기록과 코드가 갈리고, 그 갈림은 다음 세션이 응답을 다시 받아 보기 전에는 안 드러난다.
    --
    -- IMPORTANT — timestamptz는 시간대를 저장하지 않는다. 입력 오프셋을 버리고 절대 시각만
    -- 남긴다. 벽시계 시각(조기폐장 판정)은 AT TIME ZONE 'America/New_York'으로 환산해 얻는다.
    -- 서머타임은 그 시간대가 처리한다(소스도 -05:00/-04:00을 갈아 준다, 실측).
    core_start    timestamptz NOT NULL,
    core_end      timestamptz NOT NULL,

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (exchange_code, session_date),

    -- IMPORTANT — 이 CHECK가 조회표를 대신한다(V23의 ck_market_index_code, V34의
    -- ck_stock_listing_exchange와 같은 판단). 값 집합이 stock.listing_exchange와 **같아야**
    -- 조인이 성립하는데, 어긋나면 조회가 0행이고 그 0행은 「그날 휴장」과 구별되지 않는다.
    -- 값을 늘리려면 마이그레이션이 필요하고, 그 강제가 목적이다.
    CONSTRAINT ck_market_calendar_exchange CHECK (exchange_code IN ('NYSE', 'NASDAQ')),

    -- 개장이 폐장보다 늦은 세션은 없다. 소스가 그런 값을 주면 파싱이 틀린 것이다.
    CONSTRAINT ck_market_calendar_core CHECK (core_end > core_start)
);

COMMENT ON TABLE market_calendar IS
    '거래소 정규장 세션(Alpaca Trading API /v3/calendar). 여기 없는 날이 휴장이고, '
    '있는 날에 봉이 없으면 무거래·비활성·거래정지·수집 실패 중 하나인데 넷은 아직 안 갈린다. '
    'exchange_code는 stock.listing_exchange와 같은 값 집합을 쓰며 그것이 조인 키다 — '
    '국내 종목은 그 컬럼이 NULL이라 이 표에 원리적으로 안 걸린다.';
COMMENT ON COLUMN market_calendar.session_date IS
    '시장 로컬 날짜(소스 date 원문). MUST NOT: core_start에서 유도하지 않는다 — '
    'UTC 환산이 뉴욕 오후를 다음 날로 민다.';
COMMENT ON COLUMN market_calendar.core_end IS
    '정규장 종료 시각. 조기폐장일에 앞당겨진다(2024년 실측 3일: 07-03·11-29·12-24, 전부 13:00). '
    '벽시계 시각은 AT TIME ZONE ''America/New_York''으로 얻는다.';

-- 인덱스를 만들지 않는다. 읽기는 (거래소, 날짜) 단건이거나 거래소 하나의 구간이라 PK 앞부분이
-- 그대로 접근 경로다.

-- ---------------------------------------------------------------------------
-- 조인 계약을 컬럼 주석에 못 박는다
-- ---------------------------------------------------------------------------
-- COMMENT ON은 주석이 아니라 실행되는 DDL이고 그 문장이 카탈로그에 저장된다. V34의 문장을
-- 파일에서 고치면 체크섬이 깨지므로 여기서 다시 단다(V29__financial_capex_comment.sql 선례).
-- 바뀐 것은 마지막 문장이다 — 캘린더가 실재하게 됐으므로 무엇과 조인되는지를 적는다.
COMMENT ON COLUMN stock.listing_exchange IS
    '상장 거래소(Alpaca /v2/assets의 exchange 원문). 국내 종목은 NULL이다. '
    '마켓 캘린더가 거래소마다 따로 조회되고(정규장 일정이 같다고 가정하지 않는다), '
    '봉 결측을 휴장·무거래·비활성·거래정지로 가르는 데 이 값이 필요하다. '
    'MUST NOT: 이 컬럼을 시장 코드로 쓰지 않는다 — 시장은 market_code(US) 하나다. '
    'MUST NOT: 여기에 업종을 넣지 않는다 — industry_code는 KSIC 전용이고, SIC 4자리가 '
    '그 CHECK를 그대로 통과해 금융업 판정(division 64~66)이 두 체계를 한 그룹으로 묶는다. '
    'market_calendar.exchange_code와의 조인 키다(ADR-0091) — 이 값이 NULL인 시장은 '
    '캘린더 판정에서 원리적으로 빠지며 그것이 의도다.';

-- ---------------------------------------------------------------------------
-- CollectionPhase에 MARKET_CALENDAR가 늘었다
-- ---------------------------------------------------------------------------
-- V5의 ck_failure_phase가 실재하므로 함께 늘린다.
--
-- MUST NOT: 이 값을 batch_execution_failure에 실제로 쓰려 하지 않는다. 처리 단위가 종목이
-- 아니라 거래소이고 그 테이블의 stock_id는 NOT NULL + FK라 넣을 값이 없다. 집계는
-- countExternally, 통지는 alertOperator로 간다. 그럼에도 제약을 늘려 두는 이유는 V23이
-- MARKET_INDEX에 적어 둔 것과 같다 — 나중에 이 단계로 process()를 부르는 코드가 생기면
-- CHECK 위반이 아니라 NOT NULL 위반으로 죽어 원인이 더 분명해진다.
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR'));
