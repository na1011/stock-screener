-- ---------------------------------------------------------------------------
-- CollectionPhase에 FULL_STATEMENT · XBRL_MATERIAL · GOVERNANCE_DERIVE가 늘었다(2026-10-31)
-- ---------------------------------------------------------------------------
-- 단계는 「받은 것의 종류」다. 실패는 같은 (종목, 단계)가 뒤에 성공해야 닫히는데, 국내 전체 재무제표(FSR) · XBRL 재료 받기가
-- FINANCIAL을, 거버넌스 유도가 GOVERNANCE를 함께 써서 받는 것이 다른 잡의 성공이 남의 실패를 닫았다(spec §4 미해결 에러 건수).
--
-- 이 제약이 enum보다 좁으면 그 단계의 실패 기록이 CHECK에 막혀 배치 전체가 죽는다 — CollectionPhaseConstraintTest가 둘을 대조한다.
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'PRICE_GAP', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR', 'FILING_LISTING', 'FILING_CONSUMPTION', 'REFRESH_BEHIND',
     'EARNINGS_RELEASE', 'FULL_STATEMENT', 'XBRL_MATERIAL', 'GOVERNANCE_DERIVE'));

-- 지난 실패와 단계 집계도 같은 종류로 옮긴다 — 안 옮기면 옛 FSR 실패가 FINANCIAL로 남아 FR 성공이 닫는다.
UPDATE batch_execution_failure f SET failed_phase = 'FULL_STATEMENT'
  FROM batch_execution e WHERE e.id = f.execution_id AND e.job_name = 'full-statement-refill' AND f.failed_phase = 'FINANCIAL';
UPDATE batch_execution_failure f SET failed_phase = 'XBRL_MATERIAL'
  FROM batch_execution e WHERE e.id = f.execution_id AND e.job_name = 'kr-xbrl-collect' AND f.failed_phase = 'FINANCIAL';
UPDATE batch_execution_failure f SET failed_phase = 'GOVERNANCE_DERIVE'
  FROM batch_execution e WHERE e.id = f.execution_id AND e.job_name = 'governance-derive' AND f.failed_phase = 'GOVERNANCE';
UPDATE batch_execution_phase p SET phase = 'FULL_STATEMENT'
  FROM batch_execution e WHERE e.id = p.execution_id AND e.job_name = 'full-statement-refill' AND p.phase = 'FINANCIAL';
UPDATE batch_execution_phase p SET phase = 'XBRL_MATERIAL'
  FROM batch_execution e WHERE e.id = p.execution_id AND e.job_name = 'kr-xbrl-collect' AND p.phase = 'FINANCIAL';
UPDATE batch_execution_phase p SET phase = 'GOVERNANCE_DERIVE'
  FROM batch_execution e WHERE e.id = p.execution_id AND e.job_name = 'governance-derive' AND p.phase = 'GOVERNANCE';
