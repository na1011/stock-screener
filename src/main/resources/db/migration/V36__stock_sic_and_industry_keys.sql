-- V36: 미국 업종(SIC)의 자리를 만들고, 업종 파티션 키에 분류 체계를 굽는다
--
-- P25의 저장 구조다(ADR-0092). ADR-0086이 이름으로 막아 둔 것을 여는 것이 아니라
-- **다른 자리를 만드는 것**이다 — industry_code는 KSIC 전용으로 그대로 둔다.
--
-- 막으려는 사고는 하나다. ck_stock_industry_code가 '^[0-9]{3,5}$'라 SIC 4자리가 그대로
-- 통과하고, 통과하면 industry_group = left(_,3) / industry_division = left(_,2)가 만들어져
-- **KSIC 종목과 SIC 종목이 한 파티션에 들어간다.** 아무 에러도 안 나고 값이 그럴듯하다.
--
-- 그 충돌은 가정이 아니라 실측이다. submissions가 주는 SIC에 0100·0200이 실재하고
-- (농업, sec-edgar-company-profile.md), KSIC 소분류에도 011이 있어 **앞 2자리가 둘 다 01**이다.


ALTER TABLE stock ADD COLUMN sic_code varchar(4);

-- SIC는 정의상 4자리이고 **소스가 앞자리 0을 채워서 준다**(실측: LMNR·ALCO=0100, CALM=0200).
-- KSIC가 3~5자리로 섞여 오는 것과 다르므로 제약도 넓히지 않는다. 어댑터가 자릿수를
-- 맞추지 않는 근거가 이 줄이다 — 맞출 일이 없다.
ALTER TABLE stock ADD CONSTRAINT ck_stock_sic_code
    CHECK (sic_code IS NULL OR sic_code ~ '^[0-9]{4}$');

-- IMPORTANT — 체계를 **행의 사실**에 묶는다. 어느 컬럼에 썼는가로 유도하면 아무것도 못 막는다:
-- CompanyProfileCollector의 UPDATE는 종목당 컬럼 하나만 쓰고 미국 행의 industry_code는
-- NULL이므로, 어댑터가 체계를 잘못 선언해 SIC를 industry_code에 써도 "한 종목에 한 체계"
-- 제약은 통과한다. 자릿수로도 못 가른다 — 4자리 KSIC가 54종목 실재한다(2026-08-29 실측).
--
-- 상호배타는 이 둘의 귀결이라 따로 걸지 않는다.
-- 적재의 실패 격리 단위가 종목이므로 위반은 그 한 종목만 실패로 기록된다.
ALTER TABLE stock ADD CONSTRAINT ck_stock_sic_is_us
    CHECK (sic_code IS NULL OR market_code = 'US');
ALTER TABLE stock ADD CONSTRAINT ck_stock_ksic_is_not_us
    CHECK (industry_code IS NULL OR market_code <> 'US');

-- 업종 상대 지표의 파티션 키. 체계 접두를 값에 구워 두 체계가 한 동치류에 못 들어가게 한다.
--
-- 이름에 sector를 쓰지 않는 것이 요점이다. ADR-0044 §검증이 SectorRelativeCalculator에
-- rg를 걸고 "sector가 나오면 표시 라벨(stock.sector)이 판정에 새어 들어간 것"이라
-- 판정하므로, 그 낱말을 쓰면 **펜스가 정반대 결론을 낸다.**
--
-- 원문에서 파생시키는 이유는 V17과 같다 — 적재 코드가 파생값을 함께 써 넣으면 나중에
-- 한쪽만 고쳐도 아무 에러가 나지 않고, 그 상태에서 종목이 조용히 다른 업종으로 묶인다.
ALTER TABLE stock ADD COLUMN industry_group_key varchar(10)
    GENERATED ALWAYS AS (
        CASE WHEN industry_code IS NOT NULL THEN 'KSIC:' || left(industry_code, 3)
             WHEN sic_code      IS NOT NULL THEN 'SIC:'  || left(sic_code, 3) END) STORED;

ALTER TABLE stock ADD COLUMN industry_division_key varchar(10)
    GENERATED ALWAYS AS (
        CASE WHEN industry_code IS NOT NULL THEN 'KSIC:' || left(industry_code, 2)
             WHEN sic_code      IS NOT NULL THEN 'SIC:'  || left(sic_code, 2) END) STORED;

-- 업종 상대 지표가 "같은 날 같은 업종의 모든 종목"을 모은다. 그 조인의 구동 컬럼이다.
CREATE INDEX idx_stock_industry_group_key ON stock (industry_group_key)
    WHERE industry_group_key IS NOT NULL;


COMMENT ON COLUMN stock.sic_code IS
    'SEC EDGAR submissions의 sic 원문(미국 SIC). 항상 4자리이고 앞자리 0도 채워서 온다. '
    'MUST NOT: 국내 종목에 넣지 않는다 — ck_stock_sic_is_us가 막는다(ADR-0092).';
COMMENT ON COLUMN stock.industry_group_key IS
    '업종 상대 지표의 1차 모집단 키. 체계 접두 + 소분류(앞 3자리)다(ADR-0092). '
    'KSIC와 SIC는 앞 2자리가 겹치므로(둘 다 01이 실재한다) 접두가 없으면 한 그룹이 된다.';
COMMENT ON COLUMN stock.industry_division_key IS
    '체계 접두 + 중분류(앞 2자리). 소분류 표본이 5 미만일 때의 폴백 키이자 금융업 판정 근거다. '
    'MUST NOT: 접두를 떼고 숫자만 비교하지 않는다 — KSIC 65(보험)와 SIC 65(부동산)가 다르다.';

-- COMMENT ON은 주석이 아니라 실행되는 DDL이고 그 문장이 카탈로그에 저장된다. V34·V35의
-- 문장을 파일에서 고치면 체크섬이 깨지므로 여기서 다시 단다(V29·V35 선례).
-- 바뀐 것은 업종 문장이다 — 금지는 그대로이고 **갈 자리가 생겼다**는 것을 잇는다.
-- MUST: V35가 뒤에 붙인 캘린더 조인 문장을 함께 싣는다. 빠뜨리면 ADR-0091의 계약이
-- 카탈로그에서 사라진다.
COMMENT ON COLUMN stock.listing_exchange IS
    '상장 거래소(Alpaca /v2/assets의 exchange 원문). 국내 종목은 NULL이다. '
    '마켓 캘린더가 거래소마다 따로 조회되고(정규장 일정이 같다고 가정하지 않는다), '
    '봉 결측을 휴장·무거래·비활성·거래정지로 가르는 데 이 값이 필요하다. '
    'MUST NOT: 이 컬럼을 시장 코드로 쓰지 않는다 — 시장은 market_code(US) 하나다. '
    'MUST NOT: 여기에 업종을 넣지 않는다 — 미국 업종은 sic_code가 담는다(ADR-0092). '
    'market_calendar.exchange_code와의 조인 키다(ADR-0091) — 이 값이 NULL인 시장은 '
    '캘린더 판정에서 원리적으로 빠지며 그것이 의도다.';
