-- V17: 종목의 업종 코드(KSIC)와 결산월
--
-- P8의 저장 구조다(ADR-0044). 업종 상대 비교(원본 명세 H-02·S-01·S-02)와 Altman Z의
-- 금융업 제외가 전부 이 컬럼 하나에 걸려 있다.
--
-- 원문을 담고 그룹 키를 생성 컬럼으로 파생시키는 이유는 **둘이 갈릴 수 없게** 하기
-- 위해서다. 적재 코드가 파생값을 함께 써 넣으면 나중에 한쪽만 고쳐도 아무 에러가 나지 않고,
-- 그 상태에서는 종목이 조용히 다른 업종으로 묶인다.


ALTER TABLE stock ADD COLUMN industry_code varchar(8);

-- IMPORTANT — DART의 induty_code는 자릿수가 3자리와 5자리로 섞여 온다(6개사 중 2개가
-- 3자리, dart-company-profile.md의 「기업개황」 절). KSIC에서 3자리는 소분류이고 5자리의 앞
-- 3자리도 소분류이므로 left(_, 3)이 두 형태 모두에서 소분류를 낸다.
ALTER TABLE stock ADD COLUMN industry_group varchar(3)
    GENERATED ALWAYS AS (left(industry_code, 3)) STORED;

ALTER TABLE stock ADD COLUMN industry_division varchar(2)
    GENERATED ALWAYS AS (left(industry_code, 2)) STORED;

-- 예상 밖 자릿수를 막는다. 4자리가 들어오면 left(_, 3)이 소분류가 아닌 것을 소분류라
-- 부르며 **조용히 다른 그룹으로 묶는다.** 빈 문자열도 같은 이유로 막는다 — industry_group이
-- ''인 유령 그룹이 생기고 그 안에서 백분위가 계산된다.
-- 적재의 실패 격리 단위가 종목이므로 위반은 그 한 종목만 실패로 기록된다.
ALTER TABLE stock ADD CONSTRAINT ck_stock_industry_code
    CHECK (industry_code IS NULL OR industry_code ~ '^[0-9]{3}$|^[0-9]{5}$');

ALTER TABLE stock ADD COLUMN accounting_month smallint
    CONSTRAINT ck_stock_accounting_month CHECK (accounting_month BETWEEN 1 AND 12);

-- 업종 상대 지표가 "같은 날 같은 업종의 모든 종목"을 모은다. 그 조인의 구동 컬럼이다.
CREATE INDEX idx_stock_industry_group ON stock (industry_group) WHERE industry_group IS NOT NULL;

COMMENT ON COLUMN stock.industry_code IS
    'DART 기업개황 induty_code 원문(KSIC). 3자리 또는 5자리로 온다. 업종 판정의 정본이다.';
COMMENT ON COLUMN stock.industry_group IS
    'KSIC 소분류(앞 3자리). 업종 상대 지표의 1차 모집단 키다(ADR-0044).';
COMMENT ON COLUMN stock.industry_division IS
    'KSIC 중분류(앞 2자리). 소분류 표본이 5 미만일 때의 폴백 키이자 금융업(64~66) 판정 근거다.';
COMMENT ON COLUMN stock.sector IS
    '표시 전용 업종 라벨. MUST NOT: 판정에 쓰지 않는다 — 판정은 industry_* 컬럼만 본다(ADR-0044).';
COMMENT ON COLUMN stock.accounting_month IS
    'DART 기업개황 acc_mt(결산월). DartFinancialAdapter.periodRange()의 12월 결산 근사를 재는 근거다.';
