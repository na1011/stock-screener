-- 법인세비용과 세전이익. 세후 투하자본이익률의 유효세율이 이 둘 위에 선다.
--
-- 저장소가 분자(법인세비용)를 8/8로 재 두고도 분모(세전이익)는 한 번도 묻지 않았다.
-- 재보니 세전이익도 8/8로 오고 계정명이 넷으로 갈린다 — 실측은 dart-financial.md의
-- 「계정별 가용성」 절이고 탐침은 DartPretaxIncomeProbeLiveTest다.
--
-- 갈리는 자리가 셋이다.
--   * 기저가 어긋난다. 법인세비용의 표준 ID는 ...IncomeTaxExpenseContinuingOperations
--     (계속영업)인데 계속영업 세전이익은 0/8로 안 온다. 총액을 분모로 쓰므로 중단영업이
--     있는 해에 세율이 어긋나고, 그 빈도는 전수가 답한다.
--   * 부호가 회사마다 다르다. 법인세비용 음수가 두 뜻이다 — 진짜 환입(순이익 > 세전)과
--     차감 항목 표기(순이익 < 세전). 크기로 읽으면 진짜 환입의 부호가 뒤집히므로
--     세전이익 − 순이익과 대조해 판정한다. 그 판단은 지표 계층이 한다.
--   * 유량이라 연간 행에만 담는다. 세후 지표가 직전 회계연도 유효세율을 TTM 영업이익에
--     적용하므로 분기 값이 필요 없고, 어댑터가 기간 가드로 막는다. 분기에 넣으면 Q4만
--     비어 TTM 4분기 합이 한 칸 빈다(cost_of_sales가 지금 그 상태다).
--
-- 새 엔드포인트가 아니다. 이미 쓰고 있는 fnlttSinglAcntAll 응답 안에 있었고 매퍼가 읽지
-- 않았을 뿐이다(ADR-0062·0067·0069와 같은 형태).
ALTER TABLE financial_statement ADD COLUMN income_tax_expense numeric(24,2);
ALTER TABLE financial_statement ADD COLUMN profit_before_tax  numeric(24,2);

COMMENT ON COLUMN financial_statement.income_tax_expense IS
    '법인세비용(계속영업 기준). 음수가 두 뜻이다 — 진짜 환입과 차감 항목 표기이며, '
    '세전이익 − 순이익과 부호를 대조해야 갈린다. 유량이라 연간 행에만 있다.';

COMMENT ON COLUMN financial_statement.profit_before_tax IS
    '세전이익(총액). 법인세비용이 계속영업 기준이라 중단영업이 있는 해에는 기저가 어긋난다 '
    '— 계속영업 세전이익은 소스가 주지 않는다(표본 0/8). 유량이라 연간 행에만 있다.';
