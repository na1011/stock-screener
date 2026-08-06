-- V7: 지표 계산 단계를 배치 실패 기록의 대상에 추가
--
-- Phase 3이 CollectionPhase.INDICATOR를 도입했다. V5의 ck_failure_phase가 그 값을 모르면
-- 지표 계산에 실패한 종목을 기록하는 INSERT가 제약 위반으로 터진다. 그 결과는 단순한 오류가
-- 아니라 실패 격리의 붕괴다 — 실패를 기록하려다 예외가 나면 그 예외가 다시 배치를 타고 올라가
-- 종목 하나의 계산 실패가 배치 전체를 멈춘다.
--
-- INDICATOR가 외부 호출 없는 유일한 단계라는 점이 이 결정의 유일한 걸림돌이었다. 그럼에도
-- 같은 테이블에 두는 이유는 관리자의 부분 재실행이 "실패한 종목 목록"이라는 하나의 질의에
-- 의존하기 때문이다. 지표 실패만 다른 곳에 두면 그 질의가 두 곳을 UNION해야 하고,
-- 재실행 경로도 둘로 갈라진다.

ALTER TABLE batch_execution_failure DROP CONSTRAINT ck_failure_phase;

ALTER TABLE batch_execution_failure ADD CONSTRAINT ck_failure_phase CHECK (failed_phase IN
    ('PRICE', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT', 'INDICATOR'));
