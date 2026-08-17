-- 자본적지출(유형자산의 취득).
--
-- 새 엔드포인트가 아니다. 이미 쓰고 있는 fnlttSinglAcntAll(전체 재무제표) 응답의 현금흐름표에
-- 들어 있는 계정과목이며, 매퍼가 읽지 않고 있었을 뿐이다(ADR-0067). 매출원가·현금이 V27에서
-- 열린 것과 같은 형태다.
--
-- 이 컬럼이 늦게 생긴 이유는 소스가 아니라 우리 기록에 있다 — 프리셋 설명이 "자본적지출을
-- 소스가 주지 않아" 빠졌다고 적고 있었는데 api-spec 어디에도 그 측정이 없었다. 재보니 8종목
-- 8/8로 왔다(dart-open-api.md §13).
--
-- MUST NOT: 절댓값을 씌워 저장하지 않는다. 실측 8종목이 전부 양수로(유출을 크기로) 왔으므로
-- 잉여현금흐름 = operating_cash_flow - capex가 그대로 맞다. 절댓값을 씌우면 음수로 오는 회사가
-- 하나라도 있을 때 그 종목의 잉여현금흐름이 두 배로 부풀고 값이 그럴듯해 드러나지 않는다.
--
-- MUST NOT: 분기 값을 저장하지 않는다. 현금흐름표의 분기 금액이 3개월인지 누적인지 재지
-- 않았다(dart-open-api.md §11-E). operating_cash_flow가 연간 전용인 것과 같은 이유다.
--
-- 기존 행은 NULL로 남는다 — V16·V27이 그랬던 것과 같고, 채우는 것은 full-statement-refill
-- 재적재의 몫이다. 그 잡이 FILL_WITHOUT_REVISION으로 쓰기 때문에 채워진다. APPEND_ONLY였다면
-- "같은 공시일 = 재수집"으로 판정해 아무 일도 하지 않았을 자리다(ADR-0040).
ALTER TABLE financial_statement
    ADD COLUMN capex numeric(24, 2);

COMMENT ON COLUMN financial_statement.capex IS
    '자본적지출(유형자산의 취득, 현금흐름표 투자활동). 유출을 양수 크기로 담는다 — '
    '잉여현금흐름 = operating_cash_flow - capex. 무형자산의 취득은 포함하지 않는다.';
