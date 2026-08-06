-- V9: 상장주식수를 시세에 함께 저장
--
-- PER·PBR·ROE의 분모인 발행주식수가 통째로 비어 있었다(실측: 398행 전부 NULL). DART 어댑터에
-- "발행주식수는 stockTotqySttus 별도 호출이 필요하다. Phase 3에서 붙인다"는 주석이 남아 있던
-- 미완성이며, 그대로 두면 재무 지표가 하나도 계산되지 않는다. 실제 적재를 돌려보고서야 드러났다 —
-- 단위 테스트는 픽스처에 주식수를 직접 넣어서 이 구멍을 지나쳤다.
--
-- 재무 테이블(financial_statement.shares_outstanding)이 아니라 시세에 두는 이유가 둘이다.
--
--   1. 주식수는 날짜별 값이다. 유상증자·액면분할로 바뀌면 그날부터 다른 값이어야 하는데,
--      분기 재무에 묶어두면 다음 공시까지 낡은 수로 나눈다. 분할 당일 PER이 튀고,
--      그 값은 그럴듯해서 아무도 눈치채지 못한다.
--   2. 이미 받고 있는 시세 응답에 lstgStCnt로 들어 있다. DART stockTotqySttus를 따로 부르면
--      종목·연도당 1콜이 추가되어 7년치에 +2,450콜이다. 같은 정보에 쿼터를 두 번 쓸 이유가 없다.
--
-- 국내 소스는 우선주를 별도 종목으로 세므로(005930 삼성전자 / 005935 삼성전자우) 이 값이
-- 보통주 수이며 PER·PBR의 분모로 정확하다. DART 발행총수는 우선주를 합산해 EPS를 과소평가한다.
--
-- financial_statement.shares_outstanding은 지우지 않는다. 소스에 따라 재무 쪽에만 있는 경우가
-- 있고(미국 어댑터가 그럴 수 있다), 지표 계층이 시세 쪽을 우선하되 없으면 재무를 보게 둔다.

ALTER TABLE price_daily ADD COLUMN listed_shares numeric(20,0);

COMMENT ON COLUMN price_daily.listed_shares IS
    '그 시점의 상장주식수(보통주). PER·PBR·ROE의 분모. 소스가 주지 않으면 NULL이다.';
