package com.stockscreener.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 재무제표 한 기(期)의 수치.
 *
 * <p>모든 금액이 nullable이다. 외부 소스가 항목을 누락하는 일이 흔하고, null이면 그 지표를
 * 계산하지 않는 것이 옳다 — 0으로 대체하면 조용히 틀린 값이 저장된다.
 *
 * <p>{@code periodEnd}가 기간의 근거다. 회계연도가 달력연도와 다를 수 있으므로
 * ({@code fiscalYear}는 라벨일 뿐) TTM 합산은 반드시 {@code periodEnd} 순으로 한다.
 *
 * <p>IMPORTANT — {@code eps}·{@code bps}는 <b>소스가 준 값을 그대로 담는 자리가 아니다.</b>
 * 스키마에 컬럼이 있어 수집은 되지만, 지표 계산은 spec §7과 {@code data-pipeline.md}에 따라
 * 언제나 자체 계산을 쓴다. 이 두 필드는 수집값과 자체 계산값을 대조해 어긋남을 발견하는
 * 용도이며, 지표 계산기가 이것을 읽어 그대로 저장하면 "외부 계산값 미의존" 원칙이 깨진다.
 *
 * <p>IMPORTANT — <b>유량과 저량을 섞지 않는다.</b> 매출·이익·현금흐름은 4분기 합(TTM)이고
 * 자산·부채·자본·유동항목·이익잉여금은 <b>최근 분기 잔액</b>이다. 저량을 합산하면 4배가 되는데
 * 값이 그럴듯해 드러나지 않는다({@code data-pipeline.md}).
 *
 * <p>IMPORTANT — <b>순이익이 둘이고 어느 쪽도 틀린 값이 아니다.</b> {@code netIncome}은
 * <b>연결 총액</b>(비지배지분 포함)이고 {@code netIncomeControlling}은 <b>지배기업 소유주
 * 귀속분</b>이다. <b>어느 쪽을 쓸지는 짝이 정한다.</b>
 * <ul>
 *   <li><b>주당 지표는 {@code netIncomeControlling}</b> — K-IFRS 1033이 주당이익을 지배기업
 *       보통주 귀속 이익으로 정의하고, 분모인 발행주식수도 보통주만이다.
 *       {@code DIVIDEND_PAYOUT}·{@code EXEC_PAY_TO_NET_INCOME}처럼 <b>순이익이 분모</b>인
 *       것도 이쪽이다 — 배당과 임원보수를 받는 몫이 지배주주의 것이다.
 *   <li><b>비율 지표는 {@code netIncome}</b> — {@code ROE}·{@code ROA}·{@code NET_MARGIN}·
 *       {@code ACCRUAL_RATIO}의 분모(자본총계·자산총계·매출·영업현금흐름)가 전부 연결
 *       전체라, <b>여기서 분자만 바꾸면 짝이 깨진다.</b>
 * </ul>
 * 실제 크기는 작지 않다 — 배당표 전수 대조 512쌍에서 <b>96쌍이 5% 초과, 65쌍이 20% 초과</b>이고
 * 지주회사에서 최대 5.48배다. <b>MUST: 직접 고르지 말고 {@code FinancialMath}의 창 단위
 * 선택을 거친다</b> — 네 분기를 더하는 자리에서 행마다 폴백하면 두 기준이 섞인 합이 된다.
 */
public record FinancialFigures(
        int fiscalYear,
        FiscalPeriod fiscalPeriod,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate publishedAt,
        String currencyCode,
        BigDecimal revenue,
        BigDecimal operatingIncome,
        BigDecimal netIncome,
        BigDecimal totalAssets,
        BigDecimal totalEquity,
        BigDecimal totalLiabilities,
        BigDecimal currentAssets,
        BigDecimal currentLiabilities,
        BigDecimal retainedEarnings,
        BigDecimal operatingCashFlow,
        BigDecimal sharesOutstanding,
        BigDecimal eps,
        BigDecimal bps,
        BigDecimal costOfSales,
        BigDecimal cashAndEquivalents,
        BigDecimal capex,
        BigDecimal inventories,
        BigDecimal netIncomeControlling,
        BigDecimal interestAndDividendIncome,
        BigDecimal noninterestIncome,
        BigDecimal interestExpense,
        BigDecimal noninterestExpense,
        BigDecimal benefitsLossesAndExpenses,
        BigDecimal grossProfit,
        BigDecimal operatingExpenses,
        BigDecimal costsAndExpenses
) {

    public enum FiscalPeriod {
        FY, Q1, Q2, Q3, Q4;

        public boolean isQuarterly() {
            return this != FY;
        }
    }
}
