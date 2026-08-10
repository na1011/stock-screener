-- V14: 배치 진행 계량 — 분모를 작업 단위로 맞추고, 도는 중에도 보이게 한다
--
-- V5는 처리 건수를 target_count / success_count 두 컬럼으로 뒀는데, 둘의 **단위가 다르다.**
--   target_count  = 대상 종목 수                   (잡이 targets.size()를 넘긴다)
--   success_count = process() 호출 성공 횟수        (종목 × 단계 × 반복)
-- 그래서 지표 백필이 관리자 화면에 `43400/350`으로 떴다. 350종목 × 124거래일 = 43400이라
-- 값은 정확했고 단위만 어긋난 것인데, 화면에서는 데이터가 부풀어 보인다.
-- 일일 갱신도 종목당 PRICE·INDICATOR 둘이라 `700/350`이었고, 부분 실패 알림은 유니버스가
-- 350종목인데 "84716종목 실패"라고 나갔다.
--
-- target_count의 뜻은 바꾸지 않는다. V5가 "대상 종목 수"로 못박았고 실데이터가 그 뜻으로
-- 들어 있다 — 뜻을 갈아타면 과거 행이 조용히 다른 것을 의미하게 된다. 분모를 새로 둔다.
--
-- 셋 다 NULL을 허용한다. 이 마이그레이션 이전 실행은 계획 수를 신고한 적이 없고, 그것을
-- 0이나 target_count로 채우면 "안 재봤다"가 "0건 계획"이나 "종목 수만큼 계획"으로 둔갑한다.
-- 화면은 NULL을 —로 그린다.

ALTER TABLE batch_execution
    ADD COLUMN planned_task_count int,
    ADD COLUMN progress_at        timestamptz,
    ADD COLUMN stage_label        varchar(80);

COMMENT ON COLUMN batch_execution.planned_task_count IS
    '계획된 작업 단위 수. 작업 단위 하나 = process() 호출 하나(종목 × 단계 × 반복)이며 '
    'success_count·failure_count와 같은 단위다. target_count(대상 종목 수)와 혼동하지 않는다.';
COMMENT ON COLUMN batch_execution.progress_at IS
    '마지막 진척 반영 시각. RUNNING인 행이 살아 있는지 죽었는지를 이 값의 나이가 가른다 — '
    '상태만으로는 구분되지 않아 고아 RUNNING 10건을 사후에 정리해야 했다.';
COMMENT ON COLUMN batch_execution.stage_label IS
    '지금 어느 구간인가. 잡이 자유 문자열로 신고하고 화면은 해석 없이 그대로 보인다 — '
    '형식을 정하면 잡이 늘 때마다 화면이 그것을 알아야 한다.';

-- 이미 끝난 행에는 진행률이 없다. 부분 인덱스로 도는 것만 훑는다(V5의 idx_batch_exec_running과 같은 판단).
CREATE INDEX idx_batch_exec_progress ON batch_execution (progress_at)
    WHERE status = 'RUNNING';


-- ---------------------------------------------------------------------------
-- batch_execution_phase — 실행 하나의 단계별 집계
--
-- BatchExecutionContext가 이미 단계별 저장 행 수를 세고 있었는데 **DB에 남기지 않았다.**
-- 그 카운터가 존재하는 이유는 실제 사고다 — 시세가 0행인 날 지표 단계가 기존 시계열을
-- 재계산해 979,541행을 써서, 합계만 보면 성공으로 보이고 금요일 종가 결손이 아무에게도
-- 알려지지 않았다. 합계로는 그 상황을 판정할 수 없는데, 정작 화면에는 합계만 있었다.
--
-- 단계를 컬럼으로 펴지 않는 이유는 CollectionPhase에 값이 계속 붙기 때문이다(V7이 INDICATOR를
-- 더했다). 컬럼으로 두면 단계를 하나 더할 때마다 마이그레이션이 필요하고, 그것은 "지표 저장은
-- 스키마 변경 없이 늘어나야 한다"는 이 프로젝트의 기준(backend.md)과 정면으로 어긋난다.
-- ---------------------------------------------------------------------------
CREATE TABLE batch_execution_phase (
    execution_id      bigint      NOT NULL REFERENCES batch_execution(id) ON DELETE CASCADE,
    -- MUST NOT: 여기에 CHECK 제약을 걸지 않는다. 값의 정본은 CollectionPhase enum이고,
    -- V5의 ck_failure_phase가 INDICATOR 때문에 V7에서 패치돼야 했던 전례가 있다. 실패 기록과
    -- 달리 이 집계는 **정상 경로**에 있어서, 제약이 어긋나면 새 단계를 쓰는 배치가 통째로
    -- 죽는다. 실패 기록 쪽 제약은 그대로 둔다 — 그쪽은 재실행 대상 선정의 정본이라
    -- 오염되면 잘못된 종목을 다시 돌리게 된다.
    phase             varchar(30) NOT NULL,
    planned_count     int         NOT NULL DEFAULT 0,
    success_count     int         NOT NULL DEFAULT 0,
    failure_count     int         NOT NULL DEFAULT 0,
    written_row_count bigint      NOT NULL DEFAULT 0,

    PRIMARY KEY (execution_id, phase),
    CONSTRAINT ck_phase_counts CHECK (
        planned_count >= 0 AND success_count >= 0 AND failure_count >= 0
        AND written_row_count >= 0)
);

COMMENT ON TABLE batch_execution_phase IS
    '실행 하나의 단계별 집계. 합계(batch_execution)로는 "그 단계가 실제로 무언가를 받아왔는가"를 '
    '판정할 수 없다 — 다른 단계가 쓴 행이 0을 덮는다.';
COMMENT ON COLUMN batch_execution_phase.written_row_count IS
    '단계마다 세는 것의 의미가 다르다. PRICE는 저장한 행이고 INDICATOR는 값이 달라진 행이다 — '
    '지표 쓰기가 같은 값을 다시 쓰지 않기 때문이다. 재계산만 한 실행이 지표 0행인 것은 정상이다.';
