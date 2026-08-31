-- 통화의 정본. market.currency_code는 유일하지 않아(KRW가 코스피·코스닥 둘) FK 부모가 못 된다.
-- CHECK 리터럴 목록으로 두지 않는 이유는 V41이 ck_market_index_code를 넓히느라 마이그레이션을
-- 하나 태운 전례가 있기 때문이다 — 통화가 늘 때는 행 하나를 넣는다.
--
-- MUST NOT: market.currency_code에 이 표의 FK를 걸지 않는다. StockRegistrarLookupTest가
-- 런타임에 ('XX','XX','XXX','UTC')를 넣어 「수집 대상 밖 시장」 갈래를 만드는데, 그 갈래를
-- 실재하는 시장으로는 만들 수 없다고 그 테스트가 적어 뒀다. 걸면 그 시험이 깨진다.
-- MUST NOT: financial_statement.currency_code에도 걸지 않는다 — 그것은 시장 통화가 아니라
-- **보고 통화**이고 실측으로 16종(CNY·JPY·EUR …)이다.
CREATE TABLE currency (
    code varchar(3)  PRIMARY KEY,
    name varchar(40) NOT NULL
);

COMMENT ON TABLE currency IS
    '시장 통화의 정본. 시장을 추가하면 그 통화를 여기 먼저 넣는다.';

INSERT INTO currency (code, name) VALUES
    ('KRW', '대한민국 원'),
    ('USD', '미국 달러');

-- 조건의 임계에 시장 축을 단다. 축은 시장 코드가 아니라 **통화**다 — KRW 하나가 코스피·코스닥
-- 둘을 덮어 시장이 늘 때 목록을 고칠 필요가 없다(ADR-0099). 시장 코드로 두면 고치지 않은
-- 조건이 새 시장까지 조용히 판정한다.
--
-- 쓰임이 **배제가 아니라 선택**이라는 것이 요점이다. 프리셋을 시장에서 빼는 대신 임계가
-- 시장을 따라간다 — ADR-0073 §규칙이 적어 둔 "미국 프리셋을 만들 때 이 임계를 복사하지 않고
-- 그 시장 분포로 다시 잰다"의 이행이다.
--
-- 기존 행의 값은 그대로 두고 threshold_currency만 나중에 채운다. NULL = 시장 중립이고,
-- 중립의 근거가 될 수 있는 것은 부호·개수·연수·백분위·상대비교뿐이다(P27 축①).
-- "비율이니까"는 근거가 아니다 — 실측으로 ATR_PCT_14 <= 5.9가 국내 39.4% 대 미국 89.7%다.

ALTER TABLE screen_condition
    ADD COLUMN threshold_currency varchar(3) REFERENCES currency(code);

COMMENT ON COLUMN screen_condition.threshold_currency IS
    '이 임계를 잰 시장의 통화. NULL이면 시장 중립(부호·개수·연수·백분위·상대비교)이라 '
    '모든 시장에 같은 값이 걸린다. 값이 있으면 그 통화가 아닌 시장은 '
    'screen_condition_threshold에서 값을 찾고, 없으면 실행을 거절한다.';

-- 리터럴이 아닌 조건에는 임계가 없다. 지표 참조는 좌우변이 같은 차원이라 시장 중립이다.
ALTER TABLE screen_condition
    ADD CONSTRAINT ck_cond_threshold_currency_literal
    CHECK (threshold_currency IS NULL OR operand_type = 'LITERAL');

-- 다른 통화의 임계. 기준 행(screen_condition)이 threshold_currency가 가리키는 통화의 값을
-- 들고 있고, 나머지 통화가 여기 들어온다. 그래서 ck_cond_operand(리터럴이면 값이 NOT NULL)를
-- 고치지 않아도 된다 — 기준 행은 언제나 값을 갖는다.
CREATE TABLE screen_condition_threshold (
    condition_id    bigint      NOT NULL REFERENCES screen_condition(id) ON DELETE CASCADE,
    currency_code   varchar(3)  NOT NULL REFERENCES currency(code),
    literal_value   numeric(24,8) NOT NULL,
    literal_value_2 numeric(24,8),
    PRIMARY KEY (condition_id, currency_code)
);

COMMENT ON TABLE screen_condition_threshold IS
    '조건 하나의 통화별 임계. 기준 통화의 값은 screen_condition에 있고 여기엔 나머지가 온다.';

-- BETWEEN의 상한 규칙을 기준 행과 같게 강제한다. 여기만 빠뜨리면 BETWEEN 조건이 그 시장에서
-- 상한 없이 판정되는데, 값이 나오므로 화면에서 드러나지 않는다.
ALTER TABLE screen_condition_threshold
    ADD CONSTRAINT ck_cond_threshold_between
    CHECK (literal_value_2 IS NULL OR literal_value_2 >= literal_value);
