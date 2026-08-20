-- Spring Batch 메타데이터를 걷는다. 근거는 ADR-0075.
--
-- V6가 만든 여섯 테이블은 운영·테스트 DB 양쪽에서 **한 행도 쓰이지 않았다.** 이 잡들이
-- Spring Batch의 Job/Step으로 돌지 않기 때문이며, 그 사실은 ADR-0037이 2026-08-10에 이미
-- 기록했다. 재시작성은 자체 batch_execution 계열과 잡별 대상 선정 쿼리의 멱등성이 들고 있다.
--
-- MUST NOT: V6를 지우지 않는다. 적용된 마이그레이션을 지우면 Flyway 체크섬이 깨진다.

DROP TABLE IF EXISTS BATCH_STEP_EXECUTION_CONTEXT;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_CONTEXT;
DROP TABLE IF EXISTS BATCH_STEP_EXECUTION;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION_PARAMS;
DROP TABLE IF EXISTS BATCH_JOB_EXECUTION;
DROP TABLE IF EXISTS BATCH_JOB_INSTANCE;

-- MUST: 시퀀스를 함께 지운다. 테이블만 지우면 셋이 영구히 남는다.
-- MUST NOT: 이름이 batch로 시작한다는 이유로 batch_execution_id_seq ·
-- batch_execution_failure_id_seq를 건드리지 않는다. 그 둘은 **살아남는 테이블의**
-- identity 시퀀스이고, 지우면 실행 이력 INSERT가 죽는다.
DROP SEQUENCE IF EXISTS BATCH_STEP_EXECUTION_SEQ;
DROP SEQUENCE IF EXISTS BATCH_JOB_EXECUTION_SEQ;
DROP SEQUENCE IF EXISTS BATCH_JOB_INSTANCE_SEQ;

-- 두 계층을 잇던 링크. 99행 전부 NULL이었고 채우는 코드(linkJobExecution)에 호출자가 없었다.
ALTER TABLE batch_execution DROP COLUMN IF EXISTS job_execution_id;

-- V5의 코멘트가 이제 없는 컬럼과 없는 테이블을 가리킨다. 함께 고친다.
COMMENT ON TABLE batch_execution IS
    '배치 실행 이력. 재시작성과 부분 재실행이 여기 얹혀 있다 — 실행마다 새 행이며, 어디까지 했는가는 잡별 대상 선정 쿼리가 도메인 데이터에서 다시 고른다(ADR-0075).';
