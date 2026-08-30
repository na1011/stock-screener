-- 지배기업 소유주 귀속 당기순이익.
--
-- 기존 net_income은 연결 총액(비지배지분 포함)이고 그대로 둔다. 뜻을 바꾸면 분모가 연결
-- 전체인 비율 지표(ROE·ROA·순이익률·발생액)가 조용히 따라 움직이고, 두 값을 대조할 근거도
-- 사라진다.
--
-- 주당 지표만 이 컬럼을 쓴다 — K-IFRS 1033이 주당이익을 「지배기업 보통주 귀속 이익」으로
-- 정의한다. 지주회사에서 그 차이가 최대 5.5배였다(배당표 전수 대조 512쌍).
--
-- NULL이 두 가지 뜻을 갖는다: 「비지배지분이 없어 소스가 행을 안 낸다」와 「아직 못 받았다」.
-- 스키마로는 못 가르므로 세어서 드러낸다(ADR §검증의 premise).
ALTER TABLE financial_statement ADD COLUMN net_income_controlling numeric(24,2);

COMMENT ON COLUMN financial_statement.net_income_controlling IS
    '지배기업 소유주 귀속 당기순이익. net_income(연결 총액)과 다르며 주당 지표의 분자다.';
