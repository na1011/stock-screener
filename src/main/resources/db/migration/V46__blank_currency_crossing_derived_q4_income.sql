-- ADR-0110. 통화가 갈린 회계연도의 유도 4분기에서 손익만 비운다.
--
-- 유도 4분기는 `FY − 분기누적`이라 회사가 그 해에 보고 통화를 갈아타면 뺄셈이 통화를
-- 건너뛴다. 실측 11행 / 9종목이고, 그중 MLR 2025는 `171,918,000`이라는 **결손처럼 보이지도
-- 않는 수**를 들고 있었다(FY 790,271,000 USD − Q1 225,651,000 GBP − …).
--
-- MUST: 지우지 않고 손익만 비운다. 잔액은 FY의 연말 잔액이라 뺄셈을 거치지 않아 참이고,
-- 행을 지우면 TTM이 `fiscal_period <> 'FY' ORDER BY period_end DESC LIMIT 4`로 뽑히므로
-- **다섯 분기에 걸친 구멍 난 합**이 된다(SecEdgarFacts.deriveFourthQuarters의 javadoc).
-- 손익이 NULL이면 FinancialMath가 null을 전파해 판정불가가 된다 — 참인 침묵이다.
--
-- MUST: 티커를 열거하지 않고 술어로 쓴다. 라벨(fiscal_year + 'Q3')로 묶으면 분기 통화가
-- {GBP, USD}인 MLR 2025가 빠진다 — Q3만 보면 안 갈린 것으로 읽힌다. FY의 기간에 포함되는
-- 분기 전체를 본다.
--
-- 이 UPDATE는 멱등하고, 고친 어댑터 넷이 같은 결과를 내므로 재적재로 되살아나지 않는다.

UPDATE financial_statement q4
SET revenue                        = NULL,
    operating_income               = NULL,
    net_income                     = NULL,
    net_income_controlling         = NULL,
    cost_of_sales                  = NULL,
    gross_profit                   = NULL,
    operating_expenses             = NULL,
    costs_and_expenses             = NULL,
    interest_and_dividend_income   = NULL,
    noninterest_income             = NULL,
    interest_expense               = NULL,
    noninterest_expense            = NULL,
    benefits_losses_and_expenses   = NULL
WHERE q4.fiscal_period = 'Q4'
  AND EXISTS (
      SELECT 1
      FROM financial_statement fy
      WHERE fy.stock_id = q4.stock_id
        AND fy.fiscal_period = 'FY'
        AND fy.fiscal_year = q4.fiscal_year
        AND EXISTS (
            SELECT 1
            FROM financial_statement q
            WHERE q.stock_id = fy.stock_id
              AND q.fiscal_period NOT IN ('FY', 'Q4')
              AND q.period_start >= fy.period_start
              AND q.period_end   <= fy.period_end
              AND q.currency_code <> fy.currency_code));
