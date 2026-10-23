-- ---------------------------------------------------------------------------
-- 실행 시점의 관문 상태 (F2b · ADR-0184 · 탐색 `explore/s-vintage/GATE-SETTINGS-PLAN.md` v3)
-- ---------------------------------------------------------------------------
-- MUST: 관문 마이그레이션이다 — V70_2 · V70_3과 함께 관문 직전에 `src/main/resources/db/migration/`으로 한 글자도 안 바꾸고 옮긴다.
--
-- 왜: 시장마다 관문이 따로 열리면(2026-10-04 사용자 결정 「시장별 관문」) 「이 실행이 어느 관문 상태로 돌았나」를 SELECT로 답해야
-- 한다 — note 꼬리는 마감마다 덮이고 실패 · 중단 경로에는 없다. 기록 계층이 시작 때 전 잡 공통으로 남긴다. NULL = 두 관문 다
-- 꺼짐(이 열이 생기기 전 행 포함 — 그때는 관문이 다 꺼져 있었다).

ALTER TABLE batch_execution ADD COLUMN gate_state varchar(16);

COMMENT ON COLUMN batch_execution.gate_state IS
    '실행 시작 때 켜져 있던 관문(us · kr · us,kr). NULL = 둘 다 꺼짐.';
