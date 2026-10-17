-- ---------------------------------------------------------------------------
-- 미국 종목 행의 신원 — 증권 실체(닻) × 등록인 시대 (ADR-0180 · 탐색 `US-IDENTITY-PLAN-1016.md` v3.1 D1~D3)
-- ---------------------------------------------------------------------------
-- 미국 행은 `ticker` 문자열로만 식별됐고 실행마다 그때의 목록(Alpaca 활성 목록 · SEC 티커 파일 · 봉의 기본 asof=오늘)으로
-- 다시 풀렸다. 08-29 뒤 개명 · 등록인 교체 · 기호 재사용마다 그 풀이가 다른 실체를 가리켰다(계획 §0 ★1~★16).
-- 이 판은 행이 가리키는 실체를 행에 저장한다.
--
-- 번호가 V69_1인 이유: 운영 적용 판이 69이고 V70(클래스 축)은 관문 마이그레이션이라 `db/testmigration`에만 있다. 이 판은
-- 관문을 기다리지 않는다(값과 설정 표 — 꺼진 상태가 매주 중복 행을 만든다). 시험 DB는 V70이 먼저 들어 있어 out-of-order로
-- 들어온다(시험 프로파일만 허용).
--
-- 쓰기 가드: V66의 이벤트 트리거가 새 두 표에 가드를 단다(보호 제외 목록에 안 넣는다 — 앱 JVM의 쓰기 세션 안에서만 쓴다).

-- ① 실체 닻 — 이 행이 가리키는 Alpaca 증권 실체는 「그 기호가 그날 가리키던 실체」다. Alpaca 봉의 `asof`가 그것을 푼다
--    (`bars(HLX, asof=08-28)` = 지금 HOS 봉 · 계획 ★3). 닻 날짜는 행마다 행이 생긴 미국 동부 날짜다 — 상수로 두면 그 뒤에
--    생긴 행이 다른 실체를 집는다.
--    등록인 — 이 행의 재무가 오는 SEC CIK. 포함 검사(저장 접수번호 ⊂ 그 CIK의 companyfacts ∪ 공시 보충)로 검증된 것만 넣는다
--    (손 잡 sec-cik-bind · 첫 FR). null이면 소비자가 오늘 SEC 티커 파일로 푼다(지금까지의 동작).
--    시대 — 이 행이 그 등록인의 증권인 기간. 등록인이 바뀌면 행을 끊는다: 앞 행 era_to = 교체일(제외) · 뒤 행 era_from = 교체일(포함).
ALTER TABLE stock
    ADD COLUMN alpaca_anchor_symbol varchar(20),
    ADD COLUMN alpaca_anchor_date   date,
    ADD COLUMN sec_cik              bigint,
    ADD COLUMN era_from             date,
    ADD COLUMN era_to               date,
    ADD CONSTRAINT ck_stock_anchor_pair CHECK ((alpaca_anchor_symbol IS NULL) = (alpaca_anchor_date IS NULL)),
    ADD CONSTRAINT ck_stock_era_order CHECK (era_from IS NULL OR era_to IS NULL OR era_from < era_to);

-- 결정적 채우기 — 기존 미국 행의 닻은 그 행의 지금 기호와 행이 생긴 날이다(행이 생긴 뒤 바뀐 표지는 0 — 개명은 지금까지
-- 새 행으로만 왔다).
UPDATE stock
SET alpaca_anchor_symbol = ticker,
    alpaca_anchor_date   = (created_at AT TIME ZONE 'America/New_York')::date
WHERE market_code = 'US';

COMMENT ON COLUMN stock.alpaca_anchor_symbol IS
    '미국: 이 행의 증권 실체를 푸는 Alpaca 기호. alpaca_anchor_date와 짝으로 bars(symbols=이것, asof=그 날짜)가 이 실체다. 개명해도 안 바뀐다';
COMMENT ON COLUMN stock.alpaca_anchor_date IS
    '미국: 닻 기호가 이 실체를 가리키던 날(행이 생긴 미국 동부 날짜)';
COMMENT ON COLUMN stock.sec_cik IS
    '미국: 이 행의 재무가 오는 SEC 등록인. 포함 검사로 검증된 것만 넣는다. null이면 오늘 SEC 티커 파일로 푼다';
COMMENT ON COLUMN stock.era_from IS
    '미국: 이 행이 이 등록인의 증권이 된 날(포함). null이면 행의 시작부터. 재무·주식수·순위 후보만 자른다 — 봉과 수정은 자르지 않는다';
COMMENT ON COLUMN stock.era_to IS
    '미국: 이 행의 등록인 시대가 끝난 날(제외). null이면 열려 있다. 지난 행은 순위 후보가 아니다';

-- 등록인 하나에 열린 시대는 한 행 — 클래스가 여럿인 등록인(GOOGL·GOOG)은 행이 여럿이라 유일 제약을 못 건다. 펜스가 「같은 실체 ·
-- 같은 등록인 · 열린 시대 두 행」을 센다(ADR-0180 §검증).
CREATE INDEX idx_stock_sec_cik ON stock (sec_cik) WHERE sec_cik IS NOT NULL;

-- ② 기호 시대 — 이 행이 이름을 바꿔 오기 전의 기호들. 개명할 때마다 한 줄이 더해진다(신원 판정의 개명 갈래).
--    소비자(기업 행위 · 배당)는 이 표 ∪ Alpaca name_changes 거꾸로 걷기로 옛 기호를 묻는다 — 표는 원장에 없는 개명(CYCN→KRSA)을
--    더하고, 걷기는 이 표가 생기기 전 개명(FLG←NYCB)을 건넌다.
--    valid_to = 그 기호를 떠난 날(제외). valid_from = 그 기호가 된 날(포함 · 모르면 null).
CREATE TABLE stock_symbol_era (
    stock_id    bigint      NOT NULL REFERENCES stock (id) ON DELETE CASCADE,
    symbol      varchar(20) NOT NULL,
    valid_from  date,
    valid_to    date        NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_stock_symbol_era PRIMARY KEY (stock_id, valid_to),
    CONSTRAINT ck_stock_symbol_era_order CHECK (valid_from IS NULL OR valid_from < valid_to)
);
COMMENT ON TABLE stock_symbol_era IS
    '미국 행이 이름을 바꿔 오기 전의 기호 시대(ADR-0180). 신원 판정과 접기 잡만 쓴다. 손으로 고치지 않는다';

-- ③ 신원 판정 기록 — 리밸런싱마다 한 실행. 판정에 쓴 SEC 티커 파일은 인메모리 판이라 DB 펜스가 못 읽는다 — 그래서 판정과 그
--    근거를 여기 남기고 펜스는 마지막 실행을 센다. RUN은 그 실행의 요약 한 줄이다(판정이 0이어도 남는다 — 「안 돌았다」와 가른다).
--    stock_id에 외래 키를 두지 않는다 — 접기 잡이 지운 행의 판정도 기록으로 남아야 한다.
CREATE TABLE identity_triage (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id bigint      NOT NULL,
    judged_at    timestamptz NOT NULL DEFAULT now(),
    stock_id     bigint,
    symbol       varchar(20),
    verdict      varchar(12) NOT NULL,
    evidence     text        NOT NULL,
    CONSTRAINT ck_identity_triage_verdict CHECK (verdict IN ('RUN', 'RENAME', 'SPLIT', 'REUSE', 'AMBIGUOUS'))
);
CREATE INDEX idx_identity_triage_execution ON identity_triage (execution_id);
COMMENT ON TABLE identity_triage IS
    '미국 신원 판정(ADR-0180): 개명 · 등록인 교체(행 끊기) · 기호 재사용 · 보류. execution_id = 그 판정을 낸 batch_execution.id';
