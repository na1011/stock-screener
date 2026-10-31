-- ---------------------------------------------------------------------------
-- CollectionPhase에 PRICE_HISTORY가 늘었다(2026-10-31)
-- ---------------------------------------------------------------------------
-- 단계는 「받은 것의 종류」다(V69_9). 초기 적재의 시세 이력(편입 종목의 7년 창)과 평일 일일 갱신의 15일 창이 PRICE를 함께 써서,
-- 긴 창 이력 받기가 실패해 남은 기록을 다음 평일 창 성공이 닫았다 — 짧은 이력이 편입 결손 신호 없이 남는 자리(ADR-0141 「남는 구멍」).
--
-- 이 제약이 enum보다 좁으면 그 단계의 실패 기록이 CHECK에 막혀 배치 전체가 죽는다 — CollectionPhaseConstraintTest가 둘을 대조한다.
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'PRICE_GAP', 'PRICE_HISTORY', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR', 'FILING_LISTING', 'FILING_CONSUMPTION', 'REFRESH_BEHIND',
     'EARNINGS_RELEASE', 'FULL_STATEMENT', 'XBRL_MATERIAL', 'GOVERNANCE_DERIVE'));

-- 지난 행은 옮기지 않는다 — 단계는 창 길이가 정하는데(7년 창만 PRICE_HISTORY · InitialLoadJob.pricePhase) 지난 행에는 창 길이가 없다
-- (09-06 실행 283의 7년 실패 뒤 286~292가 1~6년으로 재시도했다). 지난 초기 적재 PRICE 실패 16건은 전부 해결된 채라 옮겨도 동작이 같다.
