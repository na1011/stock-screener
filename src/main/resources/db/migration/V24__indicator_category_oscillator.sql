-- 지표 분류에 OSCILLATOR를 더한다(ADR-0057).
--
-- 종목 상세가 분류만 보고 구획을 그리는데(StockDetailView), 분류가 넷뿐이라 MACD·스토캐스틱·
-- 일목 구름 같은 진동 지표가 이동평균·RSI와 같은 "기술적 지표" 묶음에 통째로 섞여 있었다.
--
-- 컬럼은 varchar(20)이고 'OSCILLATOR'는 10자라 폭을 건드릴 필요가 없다.
-- 값을 더하기만 한다 — DERIVED는 뜻이 바뀌지만 문자열은 그대로 쓰므로 기존 행을 손대지 않는다.
-- 각 행의 새 분류는 애플리케이션 기동 시 IndicatorCatalogSynchronizer가 upsert로 갱신한다.
ALTER TABLE indicator DROP CONSTRAINT ck_indicator_category;
ALTER TABLE indicator ADD CONSTRAINT ck_indicator_category
    CHECK (category IN ('TECHNICAL','FUNDAMENTAL','PRICE','OSCILLATOR','DERIVED'));
