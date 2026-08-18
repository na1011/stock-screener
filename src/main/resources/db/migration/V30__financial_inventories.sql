-- 재고자산. 린치 3.14(재고 증가율 대 매출 증가율)의 원자료다.
--
-- 저장소가 이 계정을 "없다"고 적어 두고 한 번도 재본 적이 없었다. 재보니 8종목 중 7이
-- 표준코드 ifrs-full_Inventories로 왔고, 빠진 하나는 금융업이라 그 재무상태표에 재고 계열
-- 행이 아예 없다 — 결손이 아니라 업종이다. 실측은 docs/api-spec/dart-open-api.md §14.
--
-- capex와 갈리는 자리가 둘이다.
--   * 저량(잔액)이라 4분기 잔액 행에도 담는다. capex는 유량이라 연간 행에만 있다.
--   * 음수가 원리적으로 없다. capex는 회사마다 유출을 음수로 적어 지표 계층이 크기로
--     읽어야 했지만, 재고자산에 음수가 오면 그것은 표기가 아니라 이상이다.
--
-- 새 엔드포인트가 아니다. 이미 쓰고 있는 fnlttSinglAcntAll 응답 안에 있었고 매퍼가 읽지
-- 않았을 뿐이다(ADR-0062·0067과 같은 형태).
ALTER TABLE financial_statement ADD COLUMN inventories numeric(24,2);

COMMENT ON COLUMN financial_statement.inventories IS
    '재고자산(재무상태표 잔액). 금융업은 계정 자체가 없어 NULL이 정상이다. 저량이라 분기 행에도 담긴다.';
