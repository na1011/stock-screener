-- GAP_FILL의 뜻이 넓어졌다 — 컬럼 주석만 고친다(값·제약은 그대로).
--
-- V56은 「리밸런싱 꼬리가 결손 티커로 좁혀 부른 것」으로 적었는데, ADR-0166부터 14:00 기업 행위 사슬이
-- 무효화 종목으로 좁혀 부른 지표 원본층 다시 쓰기(indicator-origin-rewrite)도 GAP_FILL로 남는다
-- (IndicatorReconciler.originTrigger). 가르는 축은 「꼬리인가」가 아니라 「정기 경로가 좁혔는가」다 —
-- 정본은 BatchExecutionRecorder.TriggeredBy.GAP_FILL javadoc이고 이 주석은 그 사본이다.
COMMENT ON COLUMN batch_execution.triggered_by IS
    '무엇이 이 실행을 띄웠는가. GAP_FILL은 정기 경로가 티커로 좁혀 부른 실행이며(리밸런싱 꼬리의 결손 티커 · '
    '14:00 사슬의 무효화 종목 원본층), 신선도 판정에서 전량 실행으로 세지 않는다(JobFreshnessPolicy)';
