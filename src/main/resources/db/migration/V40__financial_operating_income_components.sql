-- 영업이익을 유도하기 위한 원자료 컬럼 여덟.
--
-- 왜 필요한가: SEC EDGAR의 OperatingIncomeLoss가 미국에서 대량으로 비는데, 그것이
-- 「없는 것」이 아니라 **US-GAAP의 표시 관행**이다. 국내 대조군이 그것을 뒤집는다 —
-- K-IFRS는 은행도 영업이익을 표시해 활성 유니버스의 금융업 50종목이 FY 영업이익을
-- 100% 갖고 그중 23종목이 OPM_MIN_5Y 값을 낸다. 미국은 같은 조건에서 금융업 170 중
-- 119(70%)가 판정불가다. **같은 이름의 조건이 시장마다 다른 것을 재고 있었다.**
--
-- 실측(막힌 미국 금융업 119종목 전량): 은행형 4요소 또는 보험형 구성요소를 가진 종목이
-- 83(69.7%)이다. 즉 구성요소는 있고 우리 태그 매핑이 제조업 모양이었다.
--
-- MUST: 여기 담는 것은 **원자료뿐**이다. 영업이익 자체를 여기서 계산해 넣지 않는다 —
-- financial_statement는 원자료만 담고 판단은 지표 계층이 한다(FreeCashFlow javadoc,
-- phase-criteria §P22 축②). 유도는 FinancialMath.operatingIncomeOf가 한다.
--
-- MUST NOT: 이 컬럼들이 비었다고 결손으로 읽지 않는다. 업종마다 내는 것이 다르다 —
-- 은행은 이자/비이자 넷을, 보험은 benefits_losses_and_expenses를, 제조업은
-- gross_profit·operating_expenses·costs_and_expenses를 낸다. 셋 다 비는 것이 정상인
-- 종목은 OperatingIncomeLoss를 직접 내는 종목이다.

-- 은행형 — (이자수익 + 비이자수익) − (이자비용 + 비이자비용)
ALTER TABLE financial_statement ADD COLUMN interest_and_dividend_income numeric(24,2);
ALTER TABLE financial_statement ADD COLUMN noninterest_income          numeric(24,2);
ALTER TABLE financial_statement ADD COLUMN interest_expense            numeric(24,2);
ALTER TABLE financial_statement ADD COLUMN noninterest_expense         numeric(24,2);

-- 보험형 — revenue − benefits_losses_and_expenses
ALTER TABLE financial_statement ADD COLUMN benefits_losses_and_expenses numeric(24,2);

-- 비금융 — gross_profit − operating_expenses, 또는 revenue − costs_and_expenses
ALTER TABLE financial_statement ADD COLUMN gross_profit        numeric(24,2);
ALTER TABLE financial_statement ADD COLUMN operating_expenses  numeric(24,2);
ALTER TABLE financial_statement ADD COLUMN costs_and_expenses  numeric(24,2);

COMMENT ON COLUMN financial_statement.interest_and_dividend_income IS
    '이자·배당수익(은행). 영업이익 유도의 수익 항이며 그 자체가 지표는 아니다.';
COMMENT ON COLUMN financial_statement.noninterest_income IS
    '비이자수익(은행). 영업이익 유도의 수익 항.';
COMMENT ON COLUMN financial_statement.interest_expense IS
    '이자비용. 영업이익 유도의 비용 항.';
COMMENT ON COLUMN financial_statement.noninterest_expense IS
    '비이자비용(은행). 영업이익 유도의 비용 항.';
COMMENT ON COLUMN financial_statement.benefits_losses_and_expenses IS
    '보험금·손실·비용(보험). revenue에서 빼면 영업이익이 된다.';
COMMENT ON COLUMN financial_statement.gross_profit IS
    '매출총이익. 소스가 직접 낸 값이며 revenue − cost_of_sales와 다를 수 있다.';
COMMENT ON COLUMN financial_statement.operating_expenses IS
    '영업비용. gross_profit에서 빼면 영업이익이 된다.';
COMMENT ON COLUMN financial_statement.costs_and_expenses IS
    '총원가·비용. revenue에서 빼면 영업이익이 된다.';
