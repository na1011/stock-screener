-- V50: 편입 원장에 「관측인가 복원인가」 평면을 더한다 (ADR-0135)
--
-- 과거 기준일 조회가 지표만 그날 것이고 대상은 오늘 명부였다. 그것을 고치려면 보존 창(255거래일)
-- 안의 과거 편입을 소스에서 복원해 넣어야 하는데, 복원분은 「그 잡이 돌았다면 편입이었을 것」이고
-- 관측분은 「편입이었던 것」이라 **정의가 다르다.**
--
-- ADR-0012 §버린 대안과 ADR-0086 §버린 대안이 기각한 것이 정확히 「한 코드의 원장에 두 정의를
-- 구분 없이 섞는 것」이다(*"기존 이력이 무엇이었는지 구별할 수 없다"* · *"어느 정의였는지 사후에
-- 구분할 수 없다"*). 이 컬럼이 그 기각이 요구한 구분자다. 표시 없이 넣는 안은 그래서 없다.
--
-- 별도 표를 쓰지 않은 이유는 비용이 아니다. ex_membership_no_overlap이 **표 하나 안에서만**
-- 겹침을 막으므로, 나누면 두 평면 사이의 겹침을 DB가 못 본다.
ALTER TABLE universe_membership
    ADD COLUMN origin varchar(16) NOT NULL DEFAULT 'OBSERVED';

COMMENT ON COLUMN universe_membership.origin IS
    'OBSERVED = 리밸런싱 잡이 실제로 그날 만든 편입. RESTORED = 그날의 소스로 사후 복원한 편입. '
    '둘은 정의가 다르므로 섞어 세지 않는다. 복원 하한 계산이 이 값에 의존한다 — '
    'origin = ''OBSERVED''의 min(effective_from)이 복원 격자의 상한이고, 그 조건을 빼면 '
    '재실행이 자기 복원분을 하한으로 읽어 격자가 통째로 빈다. '
    '되돌리기: DELETE FROM universe_membership WHERE origin = ''RESTORED''를 먼저 돌리고, '
    '그 다음 복원이 만든 stock 행(어느 편입에도·시세에도·재무에도·지표에도 안 걸리는 행)을 지운다. '
    '순서를 뒤집으면 FK로 정리가 중단된다.';

ALTER TABLE universe_membership
    ADD CONSTRAINT ck_membership_origin CHECK (origin IN ('OBSERVED', 'RESTORED'));

-- 이 CHECK가 이 결정의 중심이다. 「복원 행은 절대 열려 있지 않다」를 문서나 테스트가 아니라
-- 행 단위로 못박으면, effective_to IS NULL로 모집단을 잡는 운영 질의 25곳과 ADR premise 18개가
-- **한 줄도 안 바뀌고 바뀔 수도 없다.** UniverseReconciler가 복원분을 읽거나 닫는 갈래도
-- 원리적으로 사라진다 — 그 잡은 effective_to IS NULL인 행만 읽고 닫고 지운다.
--
-- 대가: 복원 잡이 「열어 두고 다음 스텝에서 닫는」 형태를 쓸 수 없다(PostgreSQL의 CHECK는
-- DEFERRABLE이 안 된다). 그래서 복원은 구간을 메모리에서 완성해 닫힌 채로 INSERT한다.
-- 제약이 잡의 모양을 정하는 것이고 그 방향이 옳다.
ALTER TABLE universe_membership
    ADD CONSTRAINT ck_restored_is_closed
        CHECK (origin = 'OBSERVED' OR effective_to IS NOT NULL);

-- 인덱스는 만들지 않는다. 복원이 더할 행 수는 재구성을 해봐야 알고(주당 교체 수로 곱하는 외삽은
-- 국내에서 63% 대 실측 26.7%로 2.9배 빗나간 전례가 있다), 포함 판정은 idx_membership_stock으로
-- 종목당 십수 행을 훑는다. 실제 행 수와 EXPLAIN을 보고 필요하면 그때 더한다.
