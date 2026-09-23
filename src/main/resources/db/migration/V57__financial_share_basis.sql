-- V57: 주식수의 기저를 행에 싣는다
--
-- 시가총액 = 주식수 × 종가인데 **그 둘의 기저가 다른 종목이 있다.** `AKTX`(Akari
-- Therapeutics plc ADS)는 SEC 표지가 **보통주** 155,758,529,533주를 주고 시세는 **ADS당**
-- 가격이라, 저장 시총이 1,565B로 미국 8위에 앉았다(2026-09-22 실측. 공개유동가치 $15.0M).
-- `AMRN`은 같은 모양의 20배다.
--
-- 소스는 그 비율(ADS 하나가 보통주 몇 주인가)을 주지 않는다 — 전용 태그
-- `dei:EntityListingDepositoryReceiptRatio`가 팩트 캐시 1,214종목 중 **1종목**에만 있고,
-- 종목명 텍스트는 ADS 표기 273종목 중 **74**만 비율을 적으며(`AMRN`은 ADS 표기조차 없다),
-- Alpaca 자산 마스터에는 증권 유형 필드가 없다. **그래서 비율을 일반 규칙으로 못 만든다.**
--
-- 대신 **기업행위 원장이 기저가 어긋났다는 것은 말해 준다** — 선언된 주식 교환이 있었는데
-- 주식수 계열이 그것을 안 따라갔으면 그 판의 수는 사건 전 기저다. 이 두 컬럼이 그 판정을
-- 담고, `FinancialMath.sharesOf`와 `JdbcStoredShareCounts`가 **둘 다** 읽는다. 근거와
-- 분류 실측은 ADR-0162에 있다.
--
-- MUST: 두 컬럼은 NULL을 허용한다. **NULL인 동안 값이 한 톨도 안 바뀐다** — 읽기 쪽이
-- NULL을 「보정 없음」으로 읽으므로 이 마이그레이션 단독으로는 무해하고, 그래서 계산기
-- 배포와 유도 실행 사이의 어떤 중간 상태에서도 기저가 어긋난 짝이 원리적으로 안 생긴다.
--
-- MUST NOT: `FinancialWriter.Amount` enum에 이 둘을 더하지 않는다. 더하면
-- `managedColumns`가 관할로 판정해 `fillMissing`의 `toClear`가 유도 결과를 지운다 —
-- 2026-09-22에 같은 모양으로 KOSDAQ 지표 975건이 날아갔다(`docs/progress.md` [A50]).
-- 이 둘을 쓰는 것은 적재가 아니라 `share-basis-derive`다.
ALTER TABLE financial_statement
    ADD COLUMN share_basis_multiple numeric(18, 10),
    ADD COLUMN share_basis_status   varchar(24);

COMMENT ON COLUMN financial_statement.share_basis_multiple IS
    '이 판이 실은 주식수를 시세와 같은 기저로 옮기는 배수. NULL은 1과 같다. '
    '유도가 채우며 적재는 건드리지 않는다(Amount enum 밖이다).';

COMMENT ON COLUMN financial_statement.share_basis_status IS
    'OK=기저가 맞다 · SHIFTED=사건 전 기저라 share_basis_multiple을 곱한다 · '
    'UNRESOLVED=어긋난 것은 확실한데 크기를 모른다(주식수를 안 낸다). '
    'NULL은 아직 판정하지 않았다는 뜻이고 OK와 같게 읽힌다.';
