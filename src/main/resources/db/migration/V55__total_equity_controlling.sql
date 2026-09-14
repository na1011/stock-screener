-- 지배주주 자본을 따로 담는다.
--
-- total_equity는 미국에서 StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest가
-- 첫 후보라 비지배지분을 포함하는데, net_income은 NetIncomeLoss(지배주주 귀속)가 첫 후보다.
-- 그래서 ROE가 분자와 분모의 기저가 다른 채로 나간다.
--
-- 실측(2026-09-15 · 표본 8): 대부분 오차 0이지만 지배구조가 있는 회사에서 거대하다 —
-- LNG 비지배 46.1%로 ROE 59.13% → 109.74%(+50.61%p), KKR 59.9%로 3.06% → 7.63%(+4.57%p).
-- ROE는 버핏 우량주의 필수 게이트다.
--
-- MUST NOT: total_equity의 뜻을 바꾸지 않는다. PBR·DEBT_RATIO·ALTMAN_Z는 총자산·총부채와
-- 같은 기저라야 항등식이 서므로 총자본이 옳다. 갈라 담는 것이 요점이다.
ALTER TABLE financial_statement ADD COLUMN total_equity_controlling numeric(24, 4);

COMMENT ON COLUMN financial_statement.total_equity_controlling IS
    '지배주주 귀속 자본. total_equity는 비지배지분을 포함하므로 ROE처럼 분자가 지배주주 귀속인 '
    '지표는 이 값을 분모로 쓴다. 국내는 아직 비어 있다 — 분자도 비지배 포함이라 기저가 맞는다';
