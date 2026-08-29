-- V37: 지표가 실제로 존재하는 거래일의 시장별 색인 (ADR-0095)
--
-- 화면 기준일(IndicatorCalendar)이 전 시장 max(trade_date)를 쓰고 있었다. 미국 지표가 한 행이라도
-- 생기면 국내 휴장·미국 개장일에 기준일이 미국 날짜가 되어 **국내 전 프리셋이 「조건에 맞는
-- 종목이 없다」**가 된다(보존창 안 국내 휴장 평일 13일 중 미국 개장 9일, 실측).
--
-- indicator_value에 stock을 조인하면 그 질의가 0.099ms → 1,333ms다(실측 7,179,721행). 요청마다
-- 도는 질의라 쓸 수 없어, ADR-0093이 이 하나를 미뤄 두었다.
--
-- 이 표는 **파생 색인이다.** 원본은 indicator_value이고, 언제든 그것으로 다시 지을 수 있다
-- (IndicatorTradeDateIndex.rebuild). 어긋나면 고칠 것은 이 표이지 원본이 아니다.
--
-- MUST NOT: market_calendar로 이 일을 하지 않는다(ADR-0091·0093이 금지한다). 그것은 **거래소
-- 세션 달력**이고 국내에 소스가 없다. 이 표가 담는 것은 「지표가 실제로 있는 날」이라 다른
-- 물건이다 — 지표 배치가 실패한 날은 시장이 열렸어도 여기 없는 것이 옳다.
--
-- MUST NOT: indicator_value에 market_code를 비정규화하지 않는다(ADR-0093 §버린 대안이 잰
-- 유일한 대안이 그것이다). 7백만 행(미국을 켜면 억 단위)에 컬럼을 더하는 저장 구조 변경인데,
-- 보존 정책이 시장마다 180거래일로 묶으므로 이 표는 **시장 수 × 180행**이면 된다.

CREATE TABLE indicator_trade_date (
    market_code varchar(16) NOT NULL REFERENCES market(code),
    trade_date  date        NOT NULL,

    PRIMARY KEY (market_code, trade_date)
);

COMMENT ON TABLE indicator_trade_date IS
    'indicator_value에 그 시장 종목의 값이 하나라도 있는 거래일. 화면 기준일(IndicatorCalendar)이 '
    '이것을 읽는다 — 원본에 stock을 조인하면 1,333ms이고 요청마다 돌기 때문이다(ADR-0095). '
    '파생 색인이라 원본으로 언제든 다시 짓는다. 크기는 시장 수 × 보존 180거래일에서 묶인다. '
    'MUST: 지표를 쓰거나 지우는 잡의 끝에서 다시 짓는다 — IndicatorBackfillJob과 DailyUpdateJob '
    '둘이고, 후자를 빠뜨리면 기준일이 마지막 백필 시점에 언 채 매일 하루씩 벌어진다.';
COMMENT ON COLUMN indicator_trade_date.trade_date IS
    '지표가 있는 거래일. 시장이 열렸는지가 아니라 **값이 저장됐는지**를 뜻한다 — '
    '시세는 들어왔는데 지표 배치가 실패한 날은 여기 없는 것이 옳고, '
    'IndicatorCalendar가 price_daily가 아니라 지표에 묻는 이유가 그것이다.';

-- 이미 저장된 지표에서 초기 색인을 만든다. 이 조인이 느린 그 질의이지만 여기서는 한 번뿐이다.
INSERT INTO indicator_trade_date (market_code, trade_date)
SELECT DISTINCT s.market_code, v.trade_date
FROM indicator_value v
JOIN stock s ON s.id = v.stock_id
ON CONFLICT DO NOTHING;
