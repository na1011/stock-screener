-- 원장 행이 가격 배수를 주식수 배수와 따로 들 자리를 낸다(분사 설계 1단계 — 값 무변화).
--
-- ratio는 「1주가 몇 주가 되었는가」(주식수 배수)이고, 재구축은 지금까지 그 역수를 가격계수로 썼다.
-- 분할·병합·무상증자는 두 배수가 같아 그것이 맞지만, 분사는 갈린다 — 미국 분사는 주식수 그대로(1)에
-- 가격이 자식 가치만큼 빠지고, 국내 인적분할은 주식수가 감자 비율만큼 줄면서 가격이 그 역수만큼
-- 오르지 않는다(ADR-0117 §규칙 · 탐색 브랜치 explore/spin-off/PLAN.md).
--
-- 이 단계는 컬럼만 더한다. 전 행이 NULL이고 재구축이 coalesce(price_ratio, ratio)를 읽으므로
-- 수정계수가 한 칸도 안 바뀐다. 제약(분사 승인 행은 NOT NULL 등)은 값을 채우는 단계와 함께 넣는다.
ALTER TABLE corporate_action ADD COLUMN price_ratio numeric(18,10);

ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_price_ratio_positive
    CHECK (price_ratio IS NULL OR price_ratio > 0);

COMMENT ON COLUMN corporate_action.price_ratio IS
    '가격 배수 — 재구축이 사건 전 봉에 1/price_ratio를 곱한다. NULL이면 ratio(주식수 배수)와 같다. '
    '분사처럼 주식수와 가격이 다르게 바뀌는 사건만 채운다';
