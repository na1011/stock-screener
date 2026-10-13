-- ---------------------------------------------------------------------------
-- 공시 소비의 근거 (ADR-0176 · 재무 동기 2부 2차)
-- ---------------------------------------------------------------------------
-- 소비 단계가 장부 행을 「소비됨」으로 표시할 때 무엇을 근거로 했는지 남긴다. 수용 기준이 행마다 근거를 묻는데(2부 계획 §5)
-- V67에는 그 칸이 없다. 표시는 그 종목을 쓴 FR·FSR과 같은 트랜잭션에서 한다 — 쓰기와 표시가 갈리지 않는다.
--
-- fr_ground:
--   COMPANYFACTS   — 그 FR이 받은 companyfacts(보충 전)가 그 접수번호의 us-gaap 팩트를 싣는다(evidence = 접수번호)
--   SUPPLEMENT     — sec_filing_supplement에 그 (접수번호, CIK)가 OK·NO_XBRL·COVERED로 있다(evidence = 상태)
--   NO_XBRL        — submissions가 그 접수번호를 XBRL 없음으로 싣는다(evidence = 'isXBRL=0')
--   DART_RESPONSE  — 그 FR이 받은 (사업연도, 보고서 코드) 응답의 접수번호가 목록 접수번호 이상이다(evidence = 응답 접수번호)
-- fsr_evidence: 국내 FSR 응답의 접수번호(근거는 늘 DART_RESPONSE). consumed_by_execution은 FR 몫의 실행이다.
ALTER TABLE filing_consumption
    ADD COLUMN fr_ground        varchar(16),
    ADD COLUMN fr_evidence      varchar(32),
    ADD COLUMN fsr_evidence     varchar(32),
    ADD COLUMN fsr_by_execution bigint,
    ADD CONSTRAINT ck_filing_consumption_fr_ground CHECK (
        (fr_consumed_at IS NULL AND fr_ground IS NULL AND fr_evidence IS NULL AND consumed_by_execution IS NULL)
        OR (fr_consumed_at IS NOT NULL AND fr_evidence IS NOT NULL AND consumed_by_execution IS NOT NULL AND (
            (market = 'US' AND fr_ground IN ('COMPANYFACTS', 'SUPPLEMENT', 'NO_XBRL'))
            OR (market = 'KR' AND fr_ground = 'DART_RESPONSE')))),
    ADD CONSTRAINT ck_filing_consumption_fsr CHECK (
        (fsr_consumed_at IS NULL AND fsr_evidence IS NULL AND fsr_by_execution IS NULL)
        OR (fsr_consumed_at IS NOT NULL AND fsr_evidence IS NOT NULL AND fsr_by_execution IS NOT NULL
            AND fr_consumed_at IS NOT NULL));

COMMENT ON COLUMN filing_consumption.fr_ground IS
    'FR 몫을 소비됨으로 표시한 근거 — COMPANYFACTS · SUPPLEMENT · NO_XBRL(미국) · DART_RESPONSE(국내)';
COMMENT ON COLUMN filing_consumption.fr_evidence IS '근거의 값 — 접수번호 · 보충 상태 · isXBRL=0 · 응답 접수번호';
COMMENT ON COLUMN filing_consumption.consumed_by_execution IS 'FR 몫을 소비한 실행(batch_execution.id · GAP_FILL 또는 MANUAL)';
COMMENT ON COLUMN filing_consumption.fsr_evidence IS '국내 FSR 응답의 접수번호(목록 접수번호 이상)';
COMMENT ON COLUMN filing_consumption.fsr_by_execution IS 'FSR 몫을 소비한 실행(batch_execution.id)';

-- 단계 집계 이름이 늘었다(CollectionPhase.FILING_CONSUMPTION). 소비 잡은 종목 단위 실패를 안 쓰지만(FR·FSR이 각자 쓴다) 제약은
-- enum과 같게 둔다 — CollectionPhaseConstraintTest가 둘을 대조한다.
ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;
ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'PRICE_GAP', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR',
     'MARKET_INDEX', 'GOVERNANCE', 'MARKET_CALENDAR', 'FILING_LISTING', 'FILING_CONSUMPTION'));
