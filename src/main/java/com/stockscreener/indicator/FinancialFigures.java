package com.stockscreener.indicator;

import com.stockscreener.pipeline.datasource.us.shareclass.ShareClassLine;

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
 *   <li><b>분모가 연결 총량인 지표는 {@code netIncome}</b> — {@code ROA}(총자산)·
 *       {@code NET_MARGIN}(연결 총매출)·{@code ACCRUAL_RATIO}(총자산·영업현금흐름)·
 *       세율 가족({@code EFFECTIVE_TAX_RATE}·{@code ROIC_AFTERTAX*}). <b>여기서 분자만 바꾸면
 *       짝이 깨진다.</b>
 *   <li><b>{@code ROE}는 이 묶음이 아니다(2026-09-15 정정 · ADR-0159).</b> 위 셋의 분모는
 *       <b>연결 변형밖에 없지만</b>({@code total_assets}·매출·OCF) <b>{@code ROE}의 분모는
 *       자본이고 자본에는 지배주주 변형이 있다</b>({@code total_equity_controlling}).
 *       그래서 {@code ROE}는 <b>분자·분모를 함께 지배주주로</b> 옮긴다 —
 *       {@link com.stockscreener.indicator.calculator.FinancialMath}의 짝 선택을 거친다.
 *       <b>{@code PBR}도 같다</b> — 분자가 시가총액이라 분모가 지배주주 자본이어야 한다.
 *       <p><b>이 줄이 틀렸던 형태를 이름 짓는다 — 「분모가 같다」로 묶고 묶음에 근거 하나를
 *       달았다.</b> 근거는 <b>구성원마다</b> 검사한다. ADR-0153이 {@code PBR}을 같은 방식으로
 *       묶어 틀렸고, 여기가 {@code ROE}로 같은 실수를 했다.
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

        /**
         * <b>지배주주 귀속 자본.</b> {@code totalEquity}는 비지배지분을 포함하므로, 분자가
         * 지배주주 귀속인 지표({@code ROE})는 이 값을 분모로 써야 기저가 맞는다.
         * <b>국내는 비어 있다</b> — 분자도 비지배 포함이라 지금도 맞는다.
         */
        BigDecimal totalEquityControlling,
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
        BigDecimal costsAndExpenses,
        BigDecimal incomeTaxExpense,
        BigDecimal profitBeforeTax,

        /**
         * <b>이 판이 실은 주식수를 시세와 같은 기저로 옮기는 배수.</b> NULL은 1과 같다.
         * {@code share-basis-derive}가 채우고 <b>적재는 안 건드린다</b>.
         */
        BigDecimal shareBasisMultiple,

        /**
         * <b>이 판의 주식수가 시세와 같은 기저인가</b>({@link ShareBasis}). NULL은
         * {@link ShareBasis#OK}로 읽는다 — 아직 유도가 안 돈 판이고, 그때의 동작은 이 축이
         * 생기기 전과 정확히 같아야 한다.
         */
        String shareBasisStatus,

        /**
         * <b>주식수 클래스 축(미국 관문)이 이 행을 읽은 결과.</b> 관문이 꺼졌거나 국내면 {@code null}이고 그때의 동작은 이 축이
         * 생기기 전과 정확히 같다. {@code HIDDEN}이면 이 판의 주식수를 원장이 확인 못 했다 — 앞 판으로 내려가지 않는다
         * ({@link ShareClassLine.State}).
         */
        ShareClassLine.State shareClassState,

        /**
         * <b>그 공시의 EPS 분모 n</b>(10-K는 Q4 · 관문이 켜진 미국 행만) — 주식수 비 지표 셋의 두 끝이다(발행사 안에서 공시
         * 형식에 따라 기저가 안 바뀌게). 보인 판정의 것만 있다.
         */
        BigDecimal weightedShares
) {

    /** 저장 문자열을 enum으로. NULL은 {@link ShareBasis#OK}다. */
    public ShareBasis shareBasis() {
        return ShareBasis.of(shareBasisStatus);
    }


    public enum FiscalPeriod {
        FY, Q1, Q2, Q3, Q4;

        public boolean isQuarterly() {
            return this != FY;
        }
    }
}
