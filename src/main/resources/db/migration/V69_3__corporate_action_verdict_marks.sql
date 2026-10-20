-- 원장 행의 판정 표지 — 기계 보류와 사람 판정을 노트가 아니라 열로 둔다(ADR-0181 · 탐색 `HOLD-MARK-PLAN-1020.md`).
--
-- 적용 직전 가격 반증(PriceRefutation)이 APPROVED 소스 행을 OBSERVED로 보류해도 그 상태를 지키는 것이 없었다. 매일 14:00 수집이
-- 같은 일자 OBSERVED 행을 공시 값으로 덮으며 APPROVED로 되돌리고 14:16 재구축이 다시 보류했다(2026-10-20 실측: RJF 368 ·
-- CORZ 348). 사람이 승인한 행도 다음 재구축이 다시 보류했다. 노트로는 못 가린다 — 사람 판정이 note를 덮고, 분사 행은 수집이
-- note를 다시 쓴다.
--
-- 번호가 V69_3인 이유: 운영 적용 판이 69.2이고 V70(클래스 축)은 관문 마이그레이션이라 `db/testmigration`에만 있다. 이 판은
-- 값을 바꾸지 않는 열 추가라 관문을 기다리지 않는다. 시험 DB는 V70이 먼저 들어 있어 out-of-order로 들어온다(시험 프로파일만).
--
-- 데이터 채우기 없음: 배포 전에 보류된 행(held_at NULL)은 다음 수집에 한 번 더 APPROVED로 덮이고 같은 날 재구축이 held_at과
-- 함께 다시 보류한다 — 한 사슬 안에서 수렴한다.

ALTER TABLE corporate_action
    ADD COLUMN held_at   timestamptz,
    ADD COLUMN judged_at timestamptz;

COMMENT ON COLUMN corporate_action.held_at IS
    '적용 직전 가격 반증이 이 행을 OBSERVED로 보류한 시각(ADR-0181). 있으면 수집이 같은 값으로는 덮지 않는다 — 소스가 종류·비율·'
    '가격 배수·신주상장일을 바꿔 보내면 덮고 비운다(다시 판정). 사람 판정 뒤에도 이력으로 남는다';
COMMENT ON COLUMN corporate_action.judged_at IS
    '사람이 상태를 정한 마지막 시각(judge — 관리자 PUT · corporate-action-judge). 있으면 가격 반증이 다시 보류하지 않는다. '
    '값만 고친 판정(상태 없음)은 안 적는다. V69_3 앞의 사람 판정은 NULL이다(이력은 batch_execution의 corporate-action-judge). '
    'price-boundary 손 잡의 REJECTED와 행 접기(StockFold)는 이 열을 거치지 않는다';
