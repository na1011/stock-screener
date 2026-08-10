-- V15: 조건에 "필수" 여부를 더한다
--
-- SCORING은 좁히지 않는다는 것이 ADR-0003의 규칙이다 — 5개 중 4개를 만족한 종목도 그만큼의
-- 점수로 남아야 순위를 매길 수 있기 때문이다. 그런데 게이트로 거른 뒤 남은 것에 점수를 매기는
-- 전략은 그 성질만으로는 표현할 수 없다. 게이트를 가중치로 흉내내면 미충족 종목이 낮은 점수로
-- 결과에 남는데, "과열된 종목을 화면에 아예 띄우지 않는 것"이 목적인 조건에서는 그것이 곧 실패다.
--
-- 필수 여부를 조건 자체가 아니라 이 테이블(조건-소속 관계)에 두는 이유는 weight와 같다.
-- 조건은 자신이 어디에 쓰이는지 모르고, 판정 방식은 여전히 실행의 속성이다. SQL도 그대로 2개이며
-- 좁히는 일은 SQL이 아니라 판정 접기 단계에서 일어난다.

ALTER TABLE screen_condition
    ADD COLUMN is_required boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN screen_condition.is_required IS
    'SCORING에서 이 조건을 충족하지 못한 종목을 결과에서 뺀다. FILTER에서는 모든 조건이 이미 필수라 무시된다.';

-- 필수 조건은 점수에 참여하지 않는다. 통과한 종목은 정의상 필수를 전부 충족하므로 같은 몫을
-- 받고, 그러면 달성률이 언제나 높게 나와 변별이 사라진다. 이것을 규칙으로만 두면 저장 경로
-- 어딘가가 잊는 순간 조용히 깨지므로 제약으로 못박는다 — ck_cond_operand가 합타입을 DB에서
-- 강제하는 것과 같은 이유다.
ALTER TABLE screen_condition
    ADD CONSTRAINT ck_cond_required_weight CHECK (NOT is_required OR weight IS NULL);
