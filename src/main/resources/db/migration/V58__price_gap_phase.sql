-- ---------------------------------------------------------------------------
-- CollectionPhase에 PRICE_GAP이 늘었다
-- ---------------------------------------------------------------------------
-- 일일 갱신이 15일 창 밖의 시세 구멍 구간을 따로 받는다(StockRegistrar.priceGapRuns). 그 실패는
-- 종목 단위라 batch_execution_failure에 실제로 쓰인다.
--
-- MUST: PRICE와 가른다. 실패는 같은 (종목, 단계)가 뒤에 성공해야 닫히는데, 한 단계로 두면 다음 날
-- 창 수집의 성공이 구간 실패를 닫아 안 받은 구간이 해결로 접힌다.
--
-- 이 제약이 enum보다 좁으면 그 단계의 실패 기록이 CHECK에 막혀 배치 전체가 죽는다 —
-- CollectionPhaseConstraintTest가 둘을 대조한다.
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'PRICE_GAP', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR'));
