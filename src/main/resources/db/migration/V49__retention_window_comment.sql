-- V49: 보존 창이 180 → 255거래일이 되어 indicator_trade_date의 크기 서술이 낡았다 (ADR-0129)
--
-- V37이 이 표를 만들며 주석에 "크기는 시장 수 × 보존 180거래일에서 묶인다"를 적었다. 그 파일은
-- 이미 적용돼 Flyway 체크섬에 묶여 있으므로 고칠 수 없고, 주석은 DB 안에 그대로 남아
-- `\d+`로 이 표를 여는 세션에 **틀린 상한**을 말한다.
--
-- 값을 안 건드리고 주석만 바꾼다. 수를 다시 박지 않는 이유는 상수가 또 움직이면 같은 일이
-- 반복되기 때문이다 — 정본은 IndicatorRetentionPolicy.RETAINED_TRADING_DAYS 하나다.
COMMENT ON TABLE indicator_trade_date IS
    'indicator_value에 그 시장 종목의 값이 하나라도 있는 거래일. 화면 기준일(IndicatorCalendar)이 '
    '이것을 읽는다 — 원본에 stock을 조인하면 1,333ms이고 요청마다 돌기 때문이다(ADR-0095). '
    '파생 색인이라 원본으로 언제든 다시 짓는다. 크기는 시장 수 × 보존 일수에서 묶이고, 그 일수의 '
    '정본은 IndicatorRetentionPolicy.RETAINED_TRADING_DAYS다(ADR-0011·0129). '
    'MUST: 지표를 쓰거나 지우는 잡의 끝에서 다시 짓는다 — IndicatorBackfillJob과 DailyUpdateJob '
    '둘이고, 후자를 빠뜨리면 기준일이 마지막 백필 시점에 언 채 매일 하루씩 벌어진다.';
