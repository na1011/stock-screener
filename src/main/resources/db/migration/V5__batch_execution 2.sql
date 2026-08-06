-- V5: 배치 실행 이력과 종목 단위 실패 기록
--
-- 형태를 Phase 1에서 정하지 않고 미뤄둔 이유는, 배치의 실제 실패 단위가 정해진 뒤에야
-- 컬럼을 정당화할 수 있기 때문이다. 그 단위는 종목으로 확정되었다(data-pipeline.md).
--
-- spec §4가 요구하는 관리자 기능 세 가지가 이 두 테이블에 직접 대응한다.
--   "일자별 배치 실행 내역"              → batch_execution
--   "각 실행의 처리 건수"                → batch_execution의 카운터 컬럼들
--   "실패 종목 / 실패한 부분만 재실행"    → batch_execution_failure
--
-- IMPORTANT — Spring Batch의 BATCH_JOB_EXECUTION과 의도적으로 공존한다. 중복으로 보이지만
-- Spring Batch가 담지 않는 것이 셋이다.
--   1. 실패한 "아이템"을 구조화해 저장하지 않는다. EXIT_MESSAGE에 스택트레이스 문자열이
--      들어갈 뿐이며, 관리자의 부분 재실행이 문자열 파싱에 의존할 수는 없다.
--   2. READ_COUNT/WRITE_COUNT에 도메인 의미가 없다. "시세 몇 행, 재무 몇 건"을 구분하지 못한다.
--   3. "실패한 종목만" 쿼리하려면 종목 단위 행이 있어야 한다.
--
-- 상태·시각은 여기에도 두지만 근거는 BATCH_JOB_EXECUTION 쪽이며 이것은 조회 편의를 위한
-- 사본이다. app_user.is_admin이 allowed_email.is_admin의 사본인 것과 같은 패턴이고,
-- 그 판단은 ADR-0004에서 이미 정당화했다.


-- ---------------------------------------------------------------------------
-- batch_execution — 배치 실행 한 건
-- ---------------------------------------------------------------------------
CREATE TABLE batch_execution (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 잡의 안정적 식별자. 클래스명이 아니라 논리명을 쓴다 — 리팩터링이 이력을 깨면 안 된다.
    job_name          varchar(60)  NOT NULL,
    -- Spring Batch 실행과의 연결. NULL을 허용하는 이유는 관리자의 수동 호출 경로와,
    -- 잡 시작 직후 아직 execution id를 모르는 시점이 있기 때문이다.
    job_execution_id  bigint,
    -- 논리적 대상 일자. 실행 시각과 다르다 — 새벽에 도는 배치의 대상은 전일이고,
    -- 재실행하면 실행 시각은 바뀌지만 대상 일자는 그대로여야 한다.
    target_date       date         NOT NULL,
    status            varchar(20)  NOT NULL,
    started_at        timestamptz  NOT NULL DEFAULT now(),
    finished_at       timestamptz,

    -- 처리 건수. 도메인 의미가 있어야 관리자 화면이 "무엇이 얼마나 됐는지"를 말할 수 있다.
    target_count      int          NOT NULL DEFAULT 0,
    success_count     int          NOT NULL DEFAULT 0,
    failure_count     int          NOT NULL DEFAULT 0,
    written_row_count bigint       NOT NULL DEFAULT 0,

    -- 부분 재실행 추적. 어느 실행을 다시 돌린 것인지 알아야 "재시도했는데 또 실패"가 보인다.
    retried_from_id   bigint REFERENCES batch_execution(id),
    triggered_by      varchar(20)  NOT NULL DEFAULT 'SCHEDULER',
    note              text,

    -- PARTIAL_SUCCESS가 별도 상태인 것이 핵심이다. spec §4가 "성공/부분실패/실패"를 구분해
    -- 요약하도록 요구한다. 성공/실패 2상태로 합치면 848종목 성공에 2종목 실패한 배치가
    -- "실패"로 보여 매일 빨간불이 뜨고, 곧 아무도 그 불을 보지 않게 된다.
    CONSTRAINT ck_batch_status CHECK (status IN
        ('RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'ABORTED')),
    CONSTRAINT ck_batch_trigger CHECK (triggered_by IN ('SCHEDULER', 'ADMIN', 'MANUAL')),
    CONSTRAINT ck_batch_counts CHECK (
        target_count >= 0 AND success_count >= 0 AND failure_count >= 0 AND written_row_count >= 0),
    -- 자기 자신을 재실행 대상으로 가리킬 수 없다. 관리자 화면이 이력을 거슬러 올라갈 때 무한루프가 된다.
    CONSTRAINT ck_batch_retry_not_self CHECK (retried_from_id IS NULL OR retried_from_id <> id)
);

-- 관리자 화면의 주 조회 경로 — 최근 실행부터 일자별로 훑는다.
CREATE INDEX idx_batch_exec_recent ON batch_execution (target_date DESC, job_name);
-- 중복 기동 방지와 "지금 도는 중인 배치" 조회. RUNNING은 항상 소수이므로 부분 인덱스가 맞다.
CREATE INDEX idx_batch_exec_running ON batch_execution (status) WHERE status = 'RUNNING';

COMMENT ON TABLE batch_execution IS
    '배치 실행 이력. Spring Batch의 BATCH_JOB_EXECUTION을 대체하지 않고 job_execution_id로 참조한다.';
COMMENT ON COLUMN batch_execution.target_date IS
    '논리적 대상 일자. 실행 시각이 아니다 — 재실행해도 이 값은 바뀌지 않는다.';
COMMENT ON COLUMN batch_execution.status IS
    'PARTIAL_SUCCESS를 별도로 두는 이유는 spec §4가 성공/부분실패/실패를 구분해 요약하도록 요구하기 때문이다.';
COMMENT ON COLUMN batch_execution.written_row_count IS
    '실제로 upsert된 행 수. 대상 종목 수(target_count)와 다르다 — 한 종목이 500봉을 쓴다.';


-- ---------------------------------------------------------------------------
-- batch_execution_failure — 실패한 종목 하나
--
-- 실패 격리의 단위가 종목이라는 결정(data-pipeline.md)이 이 테이블의 존재 이유다.
-- 실패 종목을 식별할 수 없으면 관리자의 부분 재실행 기능이 성립하지 않는다.
-- ---------------------------------------------------------------------------
CREATE TABLE batch_execution_failure (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id bigint      NOT NULL REFERENCES batch_execution(id) ON DELETE CASCADE,
    -- FK를 거는 이유는 관리자 화면이 종목명을 보여줘야 하고, 재실행이 이 id를 그대로
    -- 잡 파라미터로 쓰기 때문이다.
    stock_id     bigint      NOT NULL REFERENCES stock(id),
    -- 한 종목이 시세는 성공하고 재무만 실패할 수 있다. 재실행 대상을 좁히려면
    -- 무엇이 실패했는지 알아야 한다 — 그래야 성공한 시세를 다시 받지 않는다.
    failed_phase varchar(30) NOT NULL,
    -- 어느 벤더가 깨졌는가. 대량 발생 시 소스 교체를 검토하는 판단의 근거가 된다.
    source_name  varchar(40),
    -- DataSourceException.Kind. 재시도 가치가 있는 실패인지 관리자가 알아야 한다.
    failure_kind varchar(20) NOT NULL,
    -- MUST: 실패를 조용히 넘기지 않는다(data-pipeline.md). NOT NULL인 이유가 그것이다 —
    -- 사유 없는 실패 기록은 나중에 아무것도 알려주지 않는다.
    message      text        NOT NULL,
    stack_trace  text,
    -- 재실행에 성공하면 true가 되어 "미해결 에러 건수"(spec §4) 요약에서 빠진다.
    resolved     boolean     NOT NULL DEFAULT false,
    occurred_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_failure_phase CHECK (failed_phase IN
        ('PRICE', 'FINANCIAL', 'UNIVERSE', 'SECURITY_MASTER', 'ADJUSTMENT')),
    CONSTRAINT ck_failure_kind CHECK (failure_kind IN
        ('RATE_LIMITED', 'TRANSIENT', 'NOT_FOUND', 'MALFORMED', 'AUTH', 'UNEXPECTED')),
    -- 같은 실행에서 같은 종목·단계가 두 번 기록되지 않는다. 재실행이 멱등해야 하기 때문이며,
    -- 기록 자체가 ON CONFLICT로 갱신될 수 있어야 재시도 루프가 행을 부풀리지 않는다.
    CONSTRAINT uq_failure UNIQUE (execution_id, stock_id, failed_phase)
);

-- 부분 재실행 대상 선정과 "미해결 실패" 조회의 접근 경로.
CREATE INDEX idx_failure_unresolved ON batch_execution_failure (stock_id) WHERE NOT resolved;
CREATE INDEX idx_failure_execution ON batch_execution_failure (execution_id);

COMMENT ON TABLE batch_execution_failure IS
    '종목 단위 실패 기록. 관리자의 부분 재실행이 이 행들을 대상 목록으로 읽는다.';
COMMENT ON COLUMN batch_execution_failure.failed_phase IS
    '시세만 실패한 종목의 재무를 다시 받지 않기 위해 단계를 구분한다.';
COMMENT ON COLUMN batch_execution_failure.message IS
    'NOT NULL이다. 사유 없는 실패 기록은 나중에 아무것도 알려주지 않는다.';
COMMENT ON COLUMN batch_execution_failure.failure_kind IS
    'DataSourceException.Kind + UNEXPECTED. 예상된 수집 실패와 코드 버그를 구분한다.';
