-- V34: 미국 상장 유니버스와 상장 거래소
--
-- 유니버스를 지수 편입이 아니라 **거래소 상장**으로 정의한다(ADR-0086). 지수 편입 이력은
-- 무료로 구할 경로가 없어 리밸런싱 배치의 전제가 성립하지 않는다.
--
-- market에 NYSE·NASDAQ 행을 만들지 않는다. UniverseRebalanceJob.rebalanceOne이 유니버스
-- 하나에 시장 코드 하나를 넘기므로(activeUniverses가 SELECT code, market_code FROM universe다)
-- 거래소별 시장은 유니버스도 둘로 가르게 되고, 그러면 백분위 모집단이 **상장 장소로** 갈린다
-- (ADR-0036) — NYSE와 NASDAQ의 "PER 하위 20%"가 두 벌 생긴다. 두 거래소는 통화·시간대가
-- 같아 market 행을 늘려 얻는 것도 없다. 거래소는 아래 컬럼에 담는다.


-- ---------------------------------------------------------------------------
-- stock.name 확장 — 200자로는 미국 종목을 담지 못한다
--
-- 2026-08-29 실측(AlpacaAssetProbeLiveTest): NYSE·NASDAQ 활성 8,504종목의 이름 최대 229자.
-- 긴 것은 회사명이 아니라 증권의 조건문이다:
--   "Kimco Realty Corporation Class L Depositary Shares, each of which represents a one-one
--    thousandth fractional interest in a share of 5.125% Class L Cumulative Redeemable
--    Preferred Stock, liquidation preference $25,000.00 per share"
--
-- 잘라 담으면 **서로 다른 우선주가 같은 이름이 된다.** 여유를 둬 400으로 넓힌다 —
-- varchar 길이를 늘리는 것은 PostgreSQL에서 테이블 재작성 없이 카탈로그만 바꾼다.
-- StockEntity의 @Column(length)도 함께 바꿨다.
-- ---------------------------------------------------------------------------
ALTER TABLE stock ALTER COLUMN name TYPE varchar(400);


-- ---------------------------------------------------------------------------
-- stock.listing_exchange — market_code가 US 하나인 시장에서 거래소를 가른다
-- ---------------------------------------------------------------------------
ALTER TABLE stock ADD COLUMN listing_exchange varchar(16);

-- 값 집합을 CHECK로 못 박는다. 이 phase의 범위가 NYSE·NASDAQ 둘이고, 그 밖의 값이 들어오는
-- 것은 **어댑터의 거래소 필터가 깨졌다**는 뜻이다. 넓히려면 마이그레이션이 필요하고 그 강제가
-- 목적이다(V23의 ck_market_index_code와 같은 판단).
--
-- IMPORTANT — 유니버스 반영은 종목 단위 격리를 쓰지 않으므로(ADR-0008) 이 위반은 그 실행
-- 전체를 되돌린다. 부분 적용이 없는 것이 의도다.
ALTER TABLE stock ADD CONSTRAINT ck_stock_listing_exchange
    CHECK (listing_exchange IS NULL OR listing_exchange IN ('NYSE', 'NASDAQ'));

COMMENT ON COLUMN stock.listing_exchange IS
    '상장 거래소(Alpaca /v2/assets의 exchange 원문). 국내 종목은 NULL이다. '
    '마켓 캘린더가 거래소마다 따로 조회되고(정규장 일정이 같다고 가정하지 않는다), '
    '봉 결측을 휴장·무거래·비활성·거래정지로 가르는 데 이 값이 필요하다. '
    'MUST NOT: 이 컬럼을 시장 코드로 쓰지 않는다 — 시장은 market_code(US) 하나다. '
    'MUST NOT: 여기에 업종을 넣지 않는다 — industry_code는 KSIC 전용이고, SIC 4자리가 '
    '그 CHECK를 그대로 통과해 금융업 판정(division 64~66)이 두 체계를 한 그룹으로 묶는다.';

-- 인덱스를 만들지 않는다. 읽기는 종목 하나를 짚거나 전량을 훑는 형태라 인덱스가 접근 경로를
-- 바꾸지 않는다.


-- ---------------------------------------------------------------------------
-- 유니버스
-- ---------------------------------------------------------------------------
INSERT INTO universe (code, name, market_code, is_active) VALUES
    ('US_LISTED', '미국 상장 (NYSE·NASDAQ)', 'US', false);

-- is_active = false로 넣는 이유가 둘이다.
--   1) 어댑터가 @ConditionalOnApiKey라 키 없는 환경에서 등록되지 않는다. 활성이면
--      UniverseRebalanceJob이 "소스 없음"으로 실패해 배치가 매번 PARTIAL_SUCCESS가 된다
--      (V8이 시총 유니버스에 대해 적은 것과 같은 이유다).
--   2) 시세·재무 소스가 아직 없다. 활성으로 두면 편입 종목이 스크리닝 선택지에 뜨고 값이
--      하나도 없는 화면을 보게 된다.
--
-- MUST NOT: 이 false를 "그러니 편입해도 안전하다"로 읽지 않는다. 편입 행은 유니버스를 다시
-- 내려도 남는다 — 대상 선정이 universe.is_active를 조인하게 만든 것이 ADR-0087이고, 그것이
-- 없으면 비활성으로 돌려도 종목이 배치 대상에 영구히 남는다.

-- SP500을 내린다. 행도 sp500.csv도 SeededUniverseDataSource의 담당 코드도 그대로 둔다
-- (ADR-0010의 MUST를 승계한다 — 그 MUST는 "행과 시드를 유지한다"이지 활성 여부를 말한 적이
-- 없다). 내리는 이유는 그 유니버스가 **21줄짜리 표본 CSV**를 실제 구성종목인 것처럼
-- 편입시키기 때문이다. 테스트 DB에서는 V1의 DEFAULT true 그대로 활성이었다.
--
-- PercentileCalculator가 이미 universe.is_active를 조인하므로, 내리면 US_LISTED와 겹칠
-- 경로가 **닫히는 것이 아니라 존재할 수 없게 된다**(ADR-0036의 cardinality violation).
-- 활성 유니버스는 운영에 둘·테스트에 둘이 남아 ADR-0012를 위반하지 않는다.
UPDATE universe SET is_active = false WHERE code = 'SP500';
