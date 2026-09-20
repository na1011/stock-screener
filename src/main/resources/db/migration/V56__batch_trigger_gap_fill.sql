-- 리밸런싱 꼬리가 좁혀 부르는 실행을 트리거로 가른다.
--
-- JobFreshnessPolicy는 "스케줄러 실행이 있으면 그것만 센다"로 좁은 수동 실행이 시계를
-- 되돌리는 것을 막는데, 그 방어가 서는 근거가 클래스 javadoc에 적힌 불변식 하나다 —
-- "스케줄러는 언제나 기본 경로(활성 유니버스 전량)만 돈다".
--
-- 꼬리(MembershipGapFiller)가 그 불변식을 깬다. UniverseRebalanceJob이 SCHEDULER로 돌면
-- 꼬리도 SCHEDULER를 물려받는데, 꼬리는 결손 티커 목록으로 좁혀 부른다. 실제로
-- initial-load의 SCHEDULER 행이 target_count=2로 남아 있다(2026-09-19 꼬리). 그 잡은
-- BUDGETS에서 일부러 빠져 있어 지금은 무해하지만, governance-load는 40일 예산이 걸려 있고
-- PipelineScheduler.isOverdue(25일)의 대상이기도 하다. 꼬리가 거기에 좁은 행을 하나 쓰는
-- 순간 매주 토요일이 시계를 0으로 되돌려 화요일 전량 잡이 영영 안 돌고 연체 경보도 안 뜬다.
--
-- MUST NOT: target_count로 가르지 않는다. 그 축은 JobFreshnessPolicy가 이미 근거와 함께
-- 기각했다 — 잡마다 "전량"의 크기가 다르다. company-profile의 전량은 업종이 빈 종목뿐이라
-- 실측으로 4다(2026-09-19 SCHEDULER 행). 임계를 두면 그 잡이 상시 연체가 된다.
--
-- 그래서 좁힌 실행에만 붙는 값을 더한다. company-profile은 꼬리에서도 전량을 도므로
-- 물려받은 트리거를 그대로 쓴다 — 가르는 것은 "좁혔는가"이지 "꼬리인가"가 아니다.
ALTER TABLE batch_execution DROP CONSTRAINT ck_batch_trigger;

ALTER TABLE batch_execution ADD CONSTRAINT ck_batch_trigger
    CHECK (triggered_by IN ('SCHEDULER', 'ADMIN', 'MANUAL', 'GAP_FILL'));

COMMENT ON COLUMN batch_execution.triggered_by IS
    '무엇이 이 실행을 띄웠는가. GAP_FILL은 리밸런싱 꼬리가 결손 티커로 좁혀 부른 것이며, '
    '신선도 판정에서 전량 실행으로 세지 않는다(JobFreshnessPolicy)';
