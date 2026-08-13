-- V23: 시장 지수 일별 시계열 (ADR-0053)
--
-- 지표 계층이 처음으로 **종목 밖 시계열**을 입력으로 받는다. 첫 소비자는 P12의
-- EXCESS_RETURN_26W(원본 DRV-55)이고, T-08(2년 주간 베타)이 같은 테이블을 이어받는다.
--
-- MUST NOT: price_daily에 담지 않는다. 그 테이블은 stock_id가 NOT NULL이라 종목 없는 시계열이
-- 들어갈 자리가 아니고, adj_close·adj_factor·listed_shares처럼 지수에 뜻이 없는 컬럼을 끌고 온다.
-- 지수에는 수정주가라는 개념 자체가 없다 — 분할이 없기 때문이다.

CREATE TABLE market_index_daily (
    index_code        varchar(16)    NOT NULL,
    trade_date        date           NOT NULL,
    open              numeric(18,4),
    high              numeric(18,4),
    low               numeric(18,4),
    close             numeric(18,4)  NOT NULL,
    volume            bigint,
    trade_value       numeric(20,2),

    -- 소스의 epyItmsCnt. 지수 정의가 바뀐 시점을 사후에 알 수 있는 유일한 흔적이라 담는다
    -- (코스피 829 · 코스닥 1,798, 2026-08-07 실측). 지표 계산은 쓰지 않는다.
    constituent_count int,

    created_at        timestamptz    NOT NULL DEFAULT now(),
    updated_at        timestamptz    NOT NULL DEFAULT now(),

    PRIMARY KEY (index_code, trade_date),

    -- IMPORTANT — 이 CHECK가 조회표를 대신한다. 어댑터가 'KOSPI'로 쓰고 리더가 다른 문자열로
    -- 물으면 조회는 0행이고, 그 0행이 "그날 지수가 없다"로 접혀 **전 종목의 ICHI_* 가 통째로
    -- 안 쓰인다**(ADR-0046 §승인된 이탈 2). 에러도 화면 흔적도 없는 형태이므로 여기서 막는다.
    -- 값을 늘리려면 마이그레이션이 필요하고, 그 강제가 이 제약의 목적이다.
    CONSTRAINT ck_market_index_code CHECK (index_code IN ('KOSPI', 'KOSDAQ')),

    -- 종가가 0이거나 음수인 지수는 없다. 소스가 그런 값을 주면 수집 결함이며, 0이 들어오면
    -- 26주 수익률의 분모가 되어 조용히 0%가 된다.
    CONSTRAINT ck_market_index_close CHECK (close > 0)
);

COMMENT ON TABLE market_index_daily IS
    '주가지수 일별 시계열(공공데이터포털 금융위원회_지수시세정보). '
    'index_code는 market.code와 같은 문자열을 쓰되 FK를 걸지 않는다 — 지수는 시장이 아니고, '
    '앞으로 코스피200처럼 시장에 대응하지 않는 코드가 늘 수 있다.';
COMMENT ON COLUMN market_index_daily.close IS
    '종가(소스 clpr). MUST NOT: 소스의 fltRt·vs를 저장하지 않는다 — 파생값이며 수익률은 '
    '언제나 종가에서 자체 계산한다(spec §7).';
COMMENT ON COLUMN market_index_daily.constituent_count IS
    '채용종목 수(소스 epyItmsCnt). 진단용이며 계산에 쓰지 않는다.';

-- 지표 러너가 asOf 기준 최근 N봉을 지수마다 한 번 읽는다(종목마다가 아니다). PK 앞부분이
-- index_code라 그 조회는 이미 PK로 끝난다. 별도 인덱스를 만들지 않는 이유가 그것이다.

-- CollectionPhase에 MARKET_INDEX가 늘었다. V5의 ck_failure_phase가 실재하므로 함께 늘린다.
--
-- MUST NOT: 이 값을 batch_execution_failure에 실제로 쓰려 하지 않는다. 그 테이블의 stock_id는
-- NOT NULL + FK라 **종목 밖 실패에는 넣을 값이 없다.** 지수 수집은 UNIVERSE와 같은 자리이고
-- (BatchExecutionContext.countExternally) 실패는 alertOperator로 나간다. 그럼에도 제약을
-- 늘려 두는 이유는, 나중에 이 단계로 process()를 부르는 코드가 생기면 **CHECK 위반이 아니라
-- NOT NULL 위반으로 죽어** 원인이 더 분명해지기 때문이다.
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX'));
