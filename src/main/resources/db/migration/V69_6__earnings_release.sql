-- ---------------------------------------------------------------------------
-- 실적 발표 — 지표의 재무 기준보다 새로운 실적 발표가 있는지 화면이 말하기 위한 원장 사실(ADR-0195)
-- ---------------------------------------------------------------------------
-- 값을 바꾸지 않는 표다. 지표·프리셋·워치리스트 판정은 이 표를 읽지 않고, 표시(재무 기준 · 발표 대기)만 읽는다 — 그래서 관문 뒤가
-- 아니라 main에 놓는다. 번호가 V69_6인 이유: 운영 판이 69.5이고 V70~은 관문 시험 마이그레이션이다.
--
-- 쓰기 가드: V66의 이벤트 트리거가 두 표에 가드를 단다(앱 JVM의 공시 목록 · earnings-release 잡만 쓴다).

-- ① 실적 발표 공시 한 건 = 한 행(종목이 여럿인 회사는 종목마다).
--    미국: EDGAR 일일 색인의 8-K · 8-K/A 줄(감지 범위 종목만). items는 submissions가 답한 항목 목록(「2.02,9.01」)이고, 아직 못 물었으면
--    NULL이다 — 실적 발표인지 모르는 상태라 그 종목의 그 구간은 판정 불가다. 실적 발표는 items에 2.02가 있는 행이다.
--    국내: DART 공시검색 I001 · I002 중 실적 공시 이름 규칙(DartEarningsReleaseNames)에 맞는 줄만 넣는다 — report_name이 있고 items는 NULL.
CREATE TABLE earnings_release (
    id            bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market        varchar(8)   NOT NULL,
    filing_no     varchar(32)  NOT NULL,
    stock_id      bigint       NOT NULL,
    form          varchar(32)  NOT NULL,
    report_name   varchar(200),
    items         varchar(200),
    filed_on      date         NOT NULL,
    first_seen_at timestamptz  NOT NULL DEFAULT now(),
    resolved_at   timestamptz,
    CONSTRAINT uq_earnings_release UNIQUE (market, filing_no, stock_id),
    CONSTRAINT fk_earnings_release_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT ck_earnings_release_market CHECK (market IN ('US', 'KR')),
    -- 국내는 이름으로 이미 판정한 줄만 들어온다. 미국은 items가 판정 재료다.
    CONSTRAINT ck_earnings_release_source CHECK (
        (market = 'KR' AND report_name IS NOT NULL AND items IS NULL)
        OR (market = 'US' AND report_name IS NULL AND (items IS NULL) = (resolved_at IS NULL)))
);

CREATE INDEX ix_earnings_release_stock ON earnings_release (stock_id, filed_on);
CREATE INDEX ix_earnings_release_unresolved ON earnings_release (stock_id) WHERE market = 'US' AND items IS NULL;

-- ② 종목별 과거 훑기. 일일 수집(공시 목록 커서)은 이 표가 생긴 뒤의 날만 덮는다 — 그 앞은 종목마다 원장에 한 번 물어 채우고, 어디까지
--    채웠는지(swept_through)를 남긴다. 행이 없는 종목은 발표 이력을 모른다(판정 불가).
CREATE TABLE earnings_release_sweep (
    stock_id      bigint       PRIMARY KEY,
    swept_from    date         NOT NULL,
    swept_through date         NOT NULL,
    swept_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_earnings_release_sweep_stock FOREIGN KEY (stock_id) REFERENCES stock (id),
    CONSTRAINT ck_earnings_release_sweep_range CHECK (swept_from <= swept_through)
);

-- ③ 실적 발표 잡의 실패 단위(시장)를 실패 단계로 받는다 — CollectionPhase.EARNINGS_RELEASE(CollectionPhaseConstraintTest가 enum과 대조한다).
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'PRICE_GAP', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR', 'FILING_LISTING', 'FILING_CONSUMPTION', 'REFRESH_BEHIND',
     'EARNINGS_RELEASE'));
