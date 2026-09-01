-- 인적분할을 원장의 종류로 들인다.
--
-- ADR-0049가 범위를 「액면분할·병합 + 무상증자」로 잡으면서 인적분할을 밖에 뒀다. 그 배제의
-- 근거는 **휴리스틱이 그것을 분할과 구별하지 못한다**는 것이었지 곱셈 계수로 표현할 수 없다는
-- 것이 아니었다 — 존속법인 시계열의 보정은 「1주가 몇 주가 되었는가」 그대로다.
--
-- 배제의 대가가 실물이었다. 한화(000880) 2026-08-25이 주식수 ×0.7563532970 · 종가 ×1.4093인데
-- 종류가 없어 UNKNOWN으로 남았고, UNKNOWN은 계수를 안 만들므로 52주 최고가가 두 기준을 섞어
-- 현재가가 최고 대비 -10.2%로 보였다(같은 기준으로는 -32.1%). 대안이 둘 다 나빴다 —
-- REVERSE_SPLIT으로 적으면 낱말이 거짓이 되고(ADR-0050이 경고한 자리),
-- UNSUPPORTED로 두면 그 종목이 프리셋에서 통째로 사라진다.
--
-- 존속법인 기준이므로 비율은 1보다 작다. 그리고 **스냅을 걸지 않는다** — 분할비율은 순자산
-- 비율에서 나와 단순 유리수가 아닌 것이 정상이고(실측 0.7563532970), 그 사실 자체가
-- 액면병합과 가르는 증거다.
ALTER TABLE corporate_action DROP CONSTRAINT ck_ca_type;
ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_type
    CHECK (type IN ('SPLIT', 'REVERSE_SPLIT', 'BONUS_ISSUE', 'SPIN_OFF', 'UNKNOWN'));

ALTER TABLE corporate_action DROP CONSTRAINT ck_ca_ratio_sign;
ALTER TABLE corporate_action ADD CONSTRAINT ck_ca_ratio_sign
    CHECK (
        ratio > 0 AND (
            (type = 'SPLIT'         AND ratio > 1) OR
            (type = 'REVERSE_SPLIT' AND ratio < 1) OR
            (type = 'BONUS_ISSUE'   AND ratio > 1) OR
            (type = 'SPIN_OFF'      AND ratio < 1) OR
             type = 'UNKNOWN')
    );
