-- 지표 분류에 GOVERNANCE를 더한다 (ADR-0081).
--
-- 기업의 「질」을 재는 축이 버핏·린치 프리셋에 있는데 지배구조를 재는 지표가 하나도 없었다.
-- 종목 상세와 조건 빌더가 분류만 보고 구획을 그리므로(StockDetailView·IndicatorPicker),
-- 분류를 안 열면 거버넌스 지표가 재무 지표 묶음에 섞여 들어간다.
--
-- 컬럼은 varchar(20)이고 'GOVERNANCE'는 10자라 폭을 건드릴 필요가 없다.
-- 값을 더하기만 한다 — 기존 행은 손대지 않고, 각 행의 분류는 기동 시
-- IndicatorCatalogSynchronizer가 upsert로 갱신한다. V24가 OSCILLATOR를 더한 것과 같은 형태다.
--
-- MUST NOT: 값을 빼는 마이그레이션을 만들지 않는다(ADR-0057). IndicatorCatalog의 RowMapper가
-- valueOf를 그대로 부르고 describe()는 is_active로 거르지도 않으므로, 그 문자열이 비활성
-- 행으로라도 하나 남으면 카탈로그 조회가 통째로 터진다.
ALTER TABLE indicator DROP CONSTRAINT ck_indicator_category;
ALTER TABLE indicator ADD CONSTRAINT ck_indicator_category
    CHECK (category IN ('TECHNICAL','FUNDAMENTAL','PRICE','OSCILLATOR','DERIVED','GOVERNANCE'));
