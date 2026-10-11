-- 실행이 왜 신선도 시계에 안 세는가(scope_reason)와, 마감 때 어느 시장을 못 끝냈는가(incomplete_markets) — ADR-0174.
--
-- V63은 「정기 실행과 같은 범위였는가」만 적었다. 정기 범위로 돈 무거운 잡(FR·FSR·G)이 한 시장의 소스 답을 못 받고도
-- (한도·일시 장애·소스 빈 없음) SUCCESS·PARTIAL_SUCCESS로 끝나 시계를 밀던 것을 막으려고, 마감 때 미완료 시장이 있으면
-- 같은 UPDATE에서 scope를 FULL → NARROW로 내린다(한 방향). 그러면 scope의 뜻이 「신선도 시계에 세는 실행인가」로 넓어지므로
-- 왜 NARROW인지를 따로 적는다 — 화면과 다음 세션이 「좁힌 손 실행」과 「시장 미완료」를 가를 수 있게.
--
-- MUST NOT: 이력을 UPDATE하지 않는다. V65 전 NARROW 행의 scope_reason NULL은 「사유 기록 전」이다 — 이유는 ADR-0172 §버린 대안 1.
-- MUST NOT: NOT NULL을 걸지 않는다 — 옛 jar로 되돌리면 그 INSERT가 두 컬럼을 안 넣는다. 새 NARROW 행의 사유는 앱(BatchExecutionRecorder)이 넣는다.
ALTER TABLE batch_execution ADD COLUMN scope_reason varchar(24);
ALTER TABLE batch_execution ADD COLUMN incomplete_markets text[];
ALTER TABLE batch_execution ADD CONSTRAINT ck_batch_scope_reason CHECK (scope_reason IS NULL OR scope_reason IN
    ('ARGS', 'RERUN', 'GAP_FILL', 'PAST_DATE', 'NO_SCHEDULED_RANGE', 'MARKET_INCOMPLETE'));
-- 사유와 미완료 시장은 시계에 안 세는 행에만 있다. FULL 행에 사유가 붙으면 「센 실행인데 사유가 있다」는 모순이 된다.
ALTER TABLE batch_execution ADD CONSTRAINT ck_batch_scope_reason_narrow CHECK (scope_reason IS NULL OR scope = 'NARROW');
ALTER TABLE batch_execution ADD CONSTRAINT ck_batch_incomplete_markets_narrow CHECK (incomplete_markets IS NULL OR scope = 'NARROW');
COMMENT ON COLUMN batch_execution.scope IS
    '신선도 시계에 세는 실행인가(FULL) · 아닌가(NARROW). NARROW의 사유는 scope_reason — 티커·유니버스·연수·기간·엔드포인트로 좁힘(ARGS) · '
    '재실행(RERUN) · 리밸런싱 꼬리(GAP_FILL) · 기준일이 지금이 아님(PAST_DATE) · 정기 범위가 없는 잡(NO_SCHEDULED_RANGE) · '
    '정기 범위로 돌았지만 마감 때 시장 미완료(MARKET_INCOMPLETE). NULL은 V63 전의 이력(범위를 모름 · 신선도 판정은 예전처럼 셈). '
    '신선도 판정은 NARROW를 세지 않는다(JobFreshnessPolicy · ADR-0172 · ADR-0174)';
COMMENT ON COLUMN batch_execution.scope_reason IS
    'scope가 NARROW인 이유(ADR-0174). NULL은 FULL이거나 V65 전의 NARROW(사유 기록 전)';
COMMENT ON COLUMN batch_execution.incomplete_markets IS
    '마감 때 미완료로 판정한 시장(KOSPI·KOSDAQ·US) — 시작 때 그 시장의 소스가 없었거나, 대상 실패가 전부였거나, 소스의 답을 못 받은 '
    '실패(한도·일시 장애·예상 밖 오류)가 있었다(MarketCompleteness · ADR-0174). NULL은 판정하는 잡이 아니거나 미완료가 없었다';
