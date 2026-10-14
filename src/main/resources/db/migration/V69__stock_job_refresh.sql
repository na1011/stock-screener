-- ---------------------------------------------------------------------------
-- 종목 × 무거운 잡의 「정기 범위로 맞췄다」 표지 (ADR-0177 · 편입 원자료 신선도)
-- ---------------------------------------------------------------------------
-- 리밸런싱 꼬리는 축마다 0행인 신규 편입만 채우고, FR·FSR·G 정기 실행은 25일 간격이라 원자료가 이미 있는 편입
-- (재편입 · 일괄 적재로 행이 있는 종목)은 편입 뒤 다음 정기 실행까지 낡는다(F6). 따라잡기(refresh-behind)가 「편입 뒤로
-- 정기 범위로 맞춘 적이 없는」 종목을 고르는데, 그 사실을 실행 행·실패 행에서는 못 읽는다 — 실행 행에 종목 목록이 없고,
-- 실패 행의 해결 표시는 (종목, 단계) 단위라 범위가 다른 잡끼리 서로를 해결한다.
--
-- 잡의 종목 람다가 그 종목을 쓴 같은 트랜잭션 안에서 쓴다 — 쓰기와 표지가 함께 서거나 함께 되돌아간다. 범위가 정기 실행
-- 이상일 때만 쓴다(연수 · 분기 · 기준일 · 보충 누락 0 — 각 잡의 javadoc). 실패·치명 실패·중단된 종목은 표지가 안 생긴다.
--
-- 쓰기 가드: V66의 이벤트 트리거가 이 표에 가드를 단다(잡 람다는 크론 토큰·쓰기 세션 안에서만 쓴다).
-- 시드는 없다 — 표에는 실제로 맞춘 사실만 둔다. 이 표가 생기기 전 편입의 표지 NULL은 따라잡기의 유니버스별 컷오프가 읽는다.
CREATE TABLE stock_job_refresh (
    stock_id      bigint      NOT NULL REFERENCES stock (id) ON DELETE CASCADE,
    job_name      varchar(60) NOT NULL,
    refreshed_at  timestamptz NOT NULL DEFAULT now(),
    execution_id  bigint      NOT NULL,
    CONSTRAINT pk_stock_job_refresh PRIMARY KEY (stock_id, job_name),
    CONSTRAINT ck_stock_job_refresh_job CHECK (job_name IN ('financial-refill', 'full-statement-refill', 'governance-load'))
);
COMMENT ON TABLE stock_job_refresh IS
    '종목 × 무거운 잡: 그 종목을 정기 실행 이상의 범위로 마지막에 맞춘 때(ADR-0177). 잡이 종목 쓰기와 같은 트랜잭션에서 쓴다. 손으로 고치지 않는다';
COMMENT ON COLUMN stock_job_refresh.refreshed_at IS
    '그 종목을 쓴 트랜잭션의 시작 시각. 따라잡기는 이것이 열린 편입 행의 created_at보다 이르면 뒤처졌다고 본다';
COMMENT ON COLUMN stock_job_refresh.execution_id IS
    '그 표지를 쓴 batch_execution.id — 외래 키를 두지 않는다(실행 행 정리와 독립)';

-- 따라잡기 실행의 단위(잡)를 실패 단계로 받는다 — CollectionPhase.REFRESH_BEHIND(CollectionPhaseConstraintTest가 enum과 대조한다).
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'PRICE_GAP', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR', 'FILING_LISTING', 'FILING_CONSUMPTION', 'REFRESH_BEHIND'));
