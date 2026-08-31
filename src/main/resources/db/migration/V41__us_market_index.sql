-- 미국 시장지수를 담을 수 있게 CHECK를 넓힌다.
--
-- 이 CHECK 하나가 미국에서 BETA_104W·EXCESS_RETURN_26W·EXCESS_RETURN_52W 셋을 막고 있었다.
-- 소스가 없어서가 아니다 — alpaca-bars.md가 SPY로 실측해 뒀고, FRED의 실제 S&P 500 지수와
-- 공통 거래일 1,757일을 대조해 52주 수익률 차가 평균 0.03%p · 일간 상관 0.9928이다.
-- ETF 경로는 이미 운용 중인 라이선스 안이라 새 제약이 없다(FRED는 Pre-Approval Required).
--
-- MUST: index_code는 market_code와 같은 문자열이다. IndicatorRunner가
-- `indexes.get(attributes.marketCode())`로 곧장 고르므로(ADR-0046 §승인된 이탈 4)
-- 다른 이름을 쓰면 그 시장의 지수가 영영 안 붙고 값이 안 나는 것으로만 드러난다.
-- 그래서 'SPY'가 아니라 'US'다 — 담기는 값의 출처가 SPY일 뿐이다.
ALTER TABLE market_index_daily DROP CONSTRAINT ck_market_index_code;
ALTER TABLE market_index_daily ADD CONSTRAINT ck_market_index_code
    CHECK (index_code IN ('KOSPI', 'KOSDAQ', 'US'));

COMMENT ON COLUMN market_index_daily.index_code IS
    '시장 코드와 같은 문자열. KOSPI·KOSDAQ은 지수 그 자체이고, US는 SPY(S&P 500 추종 ETF)를 대용으로 담는다.';
