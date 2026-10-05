-- 실행이 「정기 실행과 같은 범위」였는가 — [A56] 전진 수정(ADR-0172).
--
-- 무거운 잡(FR·FSR·G)은 스케줄러 성공이 0인 동안 신선도 시계를 손 실행으로 정한다(JobFreshnessPolicy 폴백).
-- 그 폴백이 범위를 안 봐서 1종목짜리 손 FR(#710 · 2026-10-03)이 FR의 첫 정기 실행을 그만큼 밀었다.
-- 앞으로의 실행은 시작할 때 범위를 찍고, 폴백과 스케줄러 갈래 둘 다 NARROW를 시계에서 뺀다.
--
-- MUST NOT: 이력을 UPDATE하지 않는다. NULL은 「기록 전」이고 신선도 판정이 예전처럼 센다 — 이유는 ADR-0172 §버린 대안.
-- MUST NOT: NOT NULL을 걸지 않는다 — 옛 jar로 되돌리면 그 INSERT가 scope를 안 넣는다. 새 행의 NULL은 앱(BatchExecutionRecorder)이 막는다.
ALTER TABLE batch_execution ADD COLUMN scope varchar(8);
ALTER TABLE batch_execution ADD CONSTRAINT ck_batch_scope CHECK (scope IS NULL OR scope IN ('FULL', 'NARROW'));
COMMENT ON COLUMN batch_execution.scope IS
    '정기 실행과 같은 범위였는가(FULL) · 티커·유니버스·연수·기간·엔드포인트·과거 기준일로 좁혔는가(NARROW). '
    'NULL은 이 컬럼 전의 이력(범위를 모름 · 신선도 판정은 예전처럼 셈). 신선도 판정은 NARROW를 세지 않는다(JobFreshnessPolicy · ADR-0172)';
