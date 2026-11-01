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
 *       보통주 귀속 이익으로 정의하고, 분모인 발행주식수도 보통주만이다. 미국 관문 켬은 거기서 우선주 배당 · 참가증권 · GP
 *       배분까지 뺀 <b>보통주 귀속 이익</b>({@link #common} · {@link #commonContinuingIncome} · ADR-0191)이다.
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
        BigDecimal weightedShares,

        /**
         * <b>보인 판정이 이 행의 n을 정했는가</b>({@link ShareClassLine.Read#judged()}). 참인데 {@link #weightedShares}가
         * {@code null}이면 판정이 「n 없음」을 정한 것이다 — 주식수 비 지표의 끝이 앞 판의 n으로 내려가지 않는다.
         */
        boolean weightedSharesJudged,

        /**
         * <b>중단영업 손익(연결 · 세후 · 비지배 몫 포함).</b> 0은 「중단영업 줄이 없다」다. {@link #discontinuedRule}이 있는데
         * {@code null}이면 원장이 그 판에서 정해 주지 않은 것이다(재분류된 해의 Q4 — 계속영업을 지어내지 않는다).
         */
        BigDecimal discontinuedIncome,

        /**
         * <b>중단영업 손익의 지배주주 몫.</b> 연결 몫이 0이 아닌데 이것이 {@code null}이면 원장이 지배 몫을 정해 주지
         * 않은 것이다 — 지배 계속영업을 지어내지 않는다({@link #continuingIncomeControlling}).
         */
        BigDecimal discontinuedIncomeControlling,

        /**
         * <b>이 행의 중단영업을 판정한 규칙의 판</b>(1 = F2b). {@code null}은 「이 규칙으로 읽지 않았다」(관문 꺼짐 · 관문 전 행 ·
         * 전체 재무제표가 안 닿은 국내 행)다 — 그때 계속영업은 순이익 그대로라 동작이 이 축이 생기기 전과 같다(ADR-0184).
         * 「안 읽음」과 「읽었는데 모름」을 두 금액 칸만으로는 못 가른다 — 앞을 모름으로 읽으면 국내 27종목(최신 TTM에 안 닿은
         * 행 84)이 통째로 미표시된다(2026-10-23 운영 SELECT).
         */
        BigDecimal discontinuedRule,

        /**
         * <b>보통주 귀속 이익</b>(미국 관문 켬만 · {@link CommonIncomeRead}). {@code null}은 「이 규칙으로 안 읽었다」(관문 꺼짐 · 국내)
         * — 그때 주당 지표 분자는 이 축이 생기기 전과 같다.
         */
        CommonIncomeRead common,

        /**
         * <b>손익 칸의 기저 대체일</b>(국내 관문 켬 · 재고 A6) — DB 컬럼명 → 그날. 뒤 보고서가 이 기간의 그 계정을 다른 기저로 다시 실었는데
         * (반기 · 3분기 누계 · 다음 해 전기 열이 저장 값과 안 닫힌다) 새 기저의 이 기간 값은 안 줬다. 그날부터의 봉은 그 칸을 모른다
         * ({@link #asSeenOn}) — 두 기저를 한 TTM 창에 섞지 않는다. 비면 이 규칙으로 안 읽은 행이다.
         */
        java.util.Map<String, FlowSupersession> flowsSupersededOn,

        /**
         * <b>4분기 행이 기대는 그 해 FY 행</b>(관문 켬 봉 사본만 · {@code IndicatorContext.visibleAt}) — 4분기로 끝나는 TTM 창의 네 분기 합은 그
         * 해 FY다. 분기 칸이 비면(기저 대체 · ADR-0200) 합 대신 이 행의 값을 쓴다(재고 「A6 남은 것」). 그 밖 행은 {@code null}.
         */
        FinancialFigures annualOfYear,

        /**
         * <b>이 판이 보이기 시작하는 날</b>(관문 켬 · 재고 「공개 시각이 공시일 칸과 갈린다」) — 공개 시각 뒤 처음 마감하는 세션의 날
         * ({@code ReportingBasis.visibleFrom}). {@code null}이면 {@link #publishedAt}이다(이 규칙으로 안 읽은 행). 보이기 소비자는
         * {@link #visibleOn()}을, 앵커 소비자(주식수 · 기업행위 배수)는 저장 공시일 {@link #publishedAt}을 읽는다.
         */
        LocalDate visibleFrom
) {

    public FinancialFigures {
        flowsSupersededOn = flowsSupersededOn == null ? java.util.Map.of() : java.util.Map.copyOf(flowsSupersededOn);
    }

    /** {@link #visibleFrom}을 안 싣는 호출 — 보이는 날은 공시일이다. */
    public FinancialFigures(int fiscalYear, FiscalPeriod fiscalPeriod, LocalDate periodStart, LocalDate periodEnd,
                            LocalDate publishedAt, String currencyCode, BigDecimal revenue, BigDecimal operatingIncome,
                            BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalEquity, BigDecimal totalEquityControlling,
                            BigDecimal totalLiabilities, BigDecimal currentAssets, BigDecimal currentLiabilities,
                            BigDecimal retainedEarnings, BigDecimal operatingCashFlow, BigDecimal sharesOutstanding, BigDecimal eps,
                            BigDecimal bps, BigDecimal costOfSales, BigDecimal cashAndEquivalents, BigDecimal capex,
                            BigDecimal inventories, BigDecimal netIncomeControlling, BigDecimal interestAndDividendIncome,
                            BigDecimal noninterestIncome, BigDecimal interestExpense, BigDecimal noninterestExpense,
                            BigDecimal benefitsLossesAndExpenses, BigDecimal grossProfit, BigDecimal operatingExpenses,
                            BigDecimal costsAndExpenses, BigDecimal incomeTaxExpense, BigDecimal profitBeforeTax,
                            BigDecimal shareBasisMultiple, String shareBasisStatus, ShareClassLine.State shareClassState,
                            BigDecimal weightedShares, boolean weightedSharesJudged, BigDecimal discontinuedIncome,
                            BigDecimal discontinuedIncomeControlling, BigDecimal discontinuedRule, CommonIncomeRead common,
                            java.util.Map<String, FlowSupersession> flowsSupersededOn, FinancialFigures annualOfYear) {
        this(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode, revenue, operatingIncome, netIncome,
                totalAssets, totalEquity, totalEquityControlling, totalLiabilities, currentAssets, currentLiabilities,
                retainedEarnings, operatingCashFlow, sharesOutstanding, eps, bps, costOfSales, cashAndEquivalents, capex,
                inventories, netIncomeControlling, interestAndDividendIncome, noninterestIncome, interestExpense,
                noninterestExpense, benefitsLossesAndExpenses, grossProfit, operatingExpenses, costsAndExpenses,
                incomeTaxExpense, profitBeforeTax, shareBasisMultiple, shareBasisStatus, shareClassState, weightedShares,
                weightedSharesJudged, discontinuedIncome, discontinuedIncomeControlling, discontinuedRule, common, flowsSupersededOn,
                annualOfYear, null);
    }

    /** 보이기 시작하는 날({@link #visibleFrom} · 없으면 공시일) — 보이기 소비자는 이것을 읽는다. */
    public LocalDate visibleOn() {
        return visibleFrom != null ? visibleFrom : publishedAt;
    }

    /** 기저 대체일을 보이는 날로 옮긴 사본(국내 관문 켬 · ADR-0204 · {@link FlowSupersession#shifted}). */
    public FinancialFigures withFlowsShifted(java.util.function.UnaryOperator<LocalDate> visible) {
        if (flowsSupersededOn.isEmpty()) {
            return this;
        }
        java.util.Map<String, FlowSupersession> moved = new java.util.HashMap<>();
        flowsSupersededOn.forEach((column, f) -> moved.put(column, f.shifted(visible)));
        return new FinancialFigures(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode, revenue,
                operatingIncome, netIncome, totalAssets, totalEquity, totalEquityControlling, totalLiabilities, currentAssets,
                currentLiabilities, retainedEarnings, operatingCashFlow, sharesOutstanding, eps, bps, costOfSales,
                cashAndEquivalents, capex, inventories, netIncomeControlling, interestAndDividendIncome, noninterestIncome,
                interestExpense, noninterestExpense, benefitsLossesAndExpenses, grossProfit, operatingExpenses, costsAndExpenses,
                incomeTaxExpense, profitBeforeTax, shareBasisMultiple, shareBasisStatus, shareClassState, weightedShares,
                weightedSharesJudged, discontinuedIncome, discontinuedIncomeControlling, discontinuedRule, common, moved,
                annualOfYear, visibleFrom);
    }

    /** 보이는 날을 단 사본. */
    public FinancialFigures withVisibleFrom(LocalDate day) {
        return new FinancialFigures(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode, revenue,
                operatingIncome, netIncome, totalAssets, totalEquity, totalEquityControlling, totalLiabilities, currentAssets,
                currentLiabilities, retainedEarnings, operatingCashFlow, sharesOutstanding, eps, bps, costOfSales,
                cashAndEquivalents, capex, inventories, netIncomeControlling, interestAndDividendIncome, noninterestIncome,
                interestExpense, noninterestExpense, benefitsLossesAndExpenses, grossProfit, operatingExpenses, costsAndExpenses,
                incomeTaxExpense, profitBeforeTax, shareBasisMultiple, shareBasisStatus, shareClassState, weightedShares,
                weightedSharesJudged, discontinuedIncome, discontinuedIncomeControlling, discontinuedRule, common, flowsSupersededOn,
                annualOfYear, day);
    }

    /** {@link #annualOfYear}를 안 싣는 호출. */
    public FinancialFigures(int fiscalYear, FiscalPeriod fiscalPeriod, LocalDate periodStart, LocalDate periodEnd,
                            LocalDate publishedAt, String currencyCode, BigDecimal revenue, BigDecimal operatingIncome,
                            BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalEquity, BigDecimal totalEquityControlling,
                            BigDecimal totalLiabilities, BigDecimal currentAssets, BigDecimal currentLiabilities,
                            BigDecimal retainedEarnings, BigDecimal operatingCashFlow, BigDecimal sharesOutstanding, BigDecimal eps,
                            BigDecimal bps, BigDecimal costOfSales, BigDecimal cashAndEquivalents, BigDecimal capex,
                            BigDecimal inventories, BigDecimal netIncomeControlling, BigDecimal interestAndDividendIncome,
                            BigDecimal noninterestIncome, BigDecimal interestExpense, BigDecimal noninterestExpense,
                            BigDecimal benefitsLossesAndExpenses, BigDecimal grossProfit, BigDecimal operatingExpenses,
                            BigDecimal costsAndExpenses, BigDecimal incomeTaxExpense, BigDecimal profitBeforeTax,
                            BigDecimal shareBasisMultiple, String shareBasisStatus, ShareClassLine.State shareClassState,
                            BigDecimal weightedShares, boolean weightedSharesJudged, BigDecimal discontinuedIncome,
                            BigDecimal discontinuedIncomeControlling, BigDecimal discontinuedRule, CommonIncomeRead common,
                            java.util.Map<String, FlowSupersession> flowsSupersededOn) {
        this(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode, revenue, operatingIncome, netIncome,
                totalAssets, totalEquity, totalEquityControlling, totalLiabilities, currentAssets, currentLiabilities,
                retainedEarnings, operatingCashFlow, sharesOutstanding, eps, bps, costOfSales, cashAndEquivalents, capex,
                inventories, netIncomeControlling, interestAndDividendIncome, noninterestIncome, interestExpense,
                noninterestExpense, benefitsLossesAndExpenses, grossProfit, operatingExpenses, costsAndExpenses,
                incomeTaxExpense, profitBeforeTax, shareBasisMultiple, shareBasisStatus, shareClassState, weightedShares,
                weightedSharesJudged, discontinuedIncome, discontinuedIncomeControlling, discontinuedRule, common, flowsSupersededOn,
                null);
    }

    /** 4분기 행에 그 해 FY 행을 단 사본({@link #annualOfYear}). */
    public FinancialFigures withAnnualOfYear(FinancialFigures annual) {
        return new FinancialFigures(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode, revenue,
                operatingIncome, netIncome, totalAssets, totalEquity, totalEquityControlling, totalLiabilities, currentAssets,
                currentLiabilities, retainedEarnings, operatingCashFlow, sharesOutstanding, eps, bps, costOfSales,
                cashAndEquivalents, capex, inventories, netIncomeControlling, interestAndDividendIncome, noninterestIncome,
                interestExpense, noninterestExpense, benefitsLossesAndExpenses, grossProfit, operatingExpenses, costsAndExpenses,
                incomeTaxExpense, profitBeforeTax, shareBasisMultiple, shareBasisStatus, shareClassState, weightedShares,
                weightedSharesJudged, discontinuedIncome, discontinuedIncomeControlling, discontinuedRule, common, flowsSupersededOn,
                annual, visibleFrom);
    }

    /** {@link #flowsSupersededOn}을 안 싣는 호출 — 이 규칙으로 안 읽은 행. */
    public FinancialFigures(int fiscalYear, FiscalPeriod fiscalPeriod, LocalDate periodStart, LocalDate periodEnd,
                            LocalDate publishedAt, String currencyCode, BigDecimal revenue, BigDecimal operatingIncome,
                            BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalEquity, BigDecimal totalEquityControlling,
                            BigDecimal totalLiabilities, BigDecimal currentAssets, BigDecimal currentLiabilities,
                            BigDecimal retainedEarnings, BigDecimal operatingCashFlow, BigDecimal sharesOutstanding, BigDecimal eps,
                            BigDecimal bps, BigDecimal costOfSales, BigDecimal cashAndEquivalents, BigDecimal capex,
                            BigDecimal inventories, BigDecimal netIncomeControlling, BigDecimal interestAndDividendIncome,
                            BigDecimal noninterestIncome, BigDecimal interestExpense, BigDecimal noninterestExpense,
                            BigDecimal benefitsLossesAndExpenses, BigDecimal grossProfit, BigDecimal operatingExpenses,
                            BigDecimal costsAndExpenses, BigDecimal incomeTaxExpense, BigDecimal profitBeforeTax,
                            BigDecimal shareBasisMultiple, String shareBasisStatus, ShareClassLine.State shareClassState,
                            BigDecimal weightedShares, boolean weightedSharesJudged, BigDecimal discontinuedIncome,
                            BigDecimal discontinuedIncomeControlling, BigDecimal discontinuedRule, CommonIncomeRead common) {
        this(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode, revenue, operatingIncome, netIncome,
                totalAssets, totalEquity, totalEquityControlling, totalLiabilities, currentAssets, currentLiabilities,
                retainedEarnings, operatingCashFlow, sharesOutstanding, eps, bps, costOfSales, cashAndEquivalents, capex,
                inventories, netIncomeControlling, interestAndDividendIncome, noninterestIncome, interestExpense,
                noninterestExpense, benefitsLossesAndExpenses, grossProfit, operatingExpenses, costsAndExpenses,
                incomeTaxExpense, profitBeforeTax, shareBasisMultiple, shareBasisStatus, shareClassState, weightedShares,
                weightedSharesJudged, discontinuedIncome, discontinuedIncomeControlling, discontinuedRule, common, null);
    }

    /**
     * {@code day}의 봉이 보는 이 행 — 기저 대체일이 그날 이하인 손익 칸을 비운다({@link #flowsSupersededOn}). 대체가 없으면 그대로다.
     */
    public FinancialFigures asSeenOn(LocalDate day) {
        if (flowsSupersededOn.isEmpty() || flowsSupersededOn.values().stream().noneMatch(f -> f.staleOn(day))) {
            return this;
        }
        java.util.function.Function<String, BigDecimal> stored = column -> switch (column) {
            case "revenue" -> revenue;
            case "operating_income" -> operatingIncome;
            case "net_income" -> netIncome;
            case "net_income_controlling" -> netIncomeControlling;
            case "cost_of_sales" -> costOfSales;
            case "gross_profit" -> grossProfit;
            case "operating_cash_flow" -> operatingCashFlow;
            case "capex" -> capex;
            case "income_tax_expense" -> incomeTaxExpense;
            case "profit_before_tax" -> profitBeforeTax;
            case "discontinued_income" -> discontinuedIncome;
            case "discontinued_income_controlling" -> discontinuedIncomeControlling;
            default -> null;
        };
        java.util.function.Function<String, BigDecimal> seen = column -> {
            FlowSupersession f = flowsSupersededOn.get(column);
            return f == null ? stored.apply(column) : f.seenOn(day, stored.apply(column));
        };
        // 순이익 · 지배주주 순이익 · 중단영업 둘은 같은 재작성을 탄다 — 한쪽만 바뀌면 창 단위 연결 폴백(FinancialMath.sumControllingContinuingIncome)이
        // 지배 계열을 연결 계속영업으로 떨어뜨리거나(F2b MUST NOT) 계속영업이 옛 중단 몫을 뺀다. 넷 중 하나가 낡으면 넷 다 새 값이 있을 때만 받고
        // 아니면 넷 다 모른다. 보통주 몫 엔진의 손익 몫도 같은 기간 손익이라 그때 함께 모른다(자본 재료는 잔액이라 그대로).
        boolean income = INCOME_GROUP.stream().anyMatch(c -> flowsSupersededOn.containsKey(c) && flowsSupersededOn.get(c).staleOn(day));
        boolean incomeKnown = !income || INCOME_GROUP.stream().allMatch(c -> !flowsSupersededOn.containsKey(c)
                ? stored.apply(c) == null : seen.apply(c) != null);
        java.util.function.Function<String, BigDecimal> value = column -> INCOME_GROUP.contains(column)
                ? (income && !incomeKnown ? null : seen.apply(column)) : seen.apply(column);
        return new FinancialFigures(fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode,
                value.apply("revenue"), value.apply("operating_income"), value.apply("net_income"), totalAssets, totalEquity,
                totalEquityControlling, totalLiabilities, currentAssets, currentLiabilities, retainedEarnings,
                value.apply("operating_cash_flow"), sharesOutstanding, eps, bps, value.apply("cost_of_sales"), cashAndEquivalents,
                value.apply("capex"), inventories, value.apply("net_income_controlling"), interestAndDividendIncome, noninterestIncome,
                interestExpense, noninterestExpense, benefitsLossesAndExpenses, value.apply("gross_profit"), operatingExpenses,
                costsAndExpenses, value.apply("income_tax_expense"), value.apply("profit_before_tax"), shareBasisMultiple,
                shareBasisStatus, shareClassState, weightedShares, weightedSharesJudged, value.apply("discontinued_income"),
                value.apply("discontinued_income_controlling"), discontinuedRule,
                income && common != null ? common.withoutIncome() : common, flowsSupersededOn, annualOfYear, visibleFrom);
    }

    /** 함께 낡는 손익 칸(DB 컬럼명) — {@link #asSeenOn}. */
    private static final java.util.Set<String> INCOME_GROUP = java.util.Set.of(
            "net_income", "net_income_controlling", "discontinued_income", "discontinued_income_controlling");

    /**
     * <b>계속영업 순이익(연결).</b> 순이익에서 중단영업을 뺀다 — 중단영업은 정의상(ASC 205-20 · K-IFRS 1105) 이어지지 않는
     * 손익이고 매각 차익을 싣는다. 이 규칙으로 안 읽은 행은 순이익 그대로, 읽었는데 연결 중단영업을 모르면 {@code null}이다.
     */
    public BigDecimal continuingIncome() {
        if (netIncome == null) {
            return null;
        }
        if (discontinuedRule == null) {
            return netIncome;
        }
        return discontinuedIncome == null ? null : netIncome.subtract(discontinuedIncome);
    }

    /**
     * <b>계속영업 순이익(지배주주).</b> 주당 지표의 분자다.
     *
     * <p>{@code null}이 둘이다 — 지배주주 순이익 자체가 없거나, 연결 중단영업은 있는데 그 지배 몫을 원장이 안 정해 줬다.
     * 뒤의 것을 「비지배 몫 0」으로 채우지 않는다: 답을 아는 칸에서 원장이 자기정합한데 그 가정이 틀린 것이 104/144다
     * (탐색 {@code F2B-PLAN-1023.md} 1b).
     */
    public BigDecimal continuingIncomeControlling() {
        return continuingOf(netIncomeControlling);
    }

    /** 지배 몫 기저 하나에서 지배 중단을 뺀다 — 갈래 넷(규칙 없음 · 지배 중단 있음 · 연결 중단 0 · 모름)이 지배 NI와 보통주 몫에 같다. */
    private BigDecimal continuingOf(BigDecimal base) {
        if (base == null) {
            return null;
        }
        if (discontinuedRule == null) {
            return base;
        }
        if (discontinuedIncomeControlling != null) {
            return base.subtract(discontinuedIncomeControlling);
        }
        if (discontinuedIncome != null && discontinuedIncome.signum() == 0) {
            return base;
        }
        return null;
    }

    /**
     * <b>주당 지표의 분자 — 보통주 귀속 계속영업 순이익</b>(탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1). 미국 관문 켬 행은 보통주 몫에서
     * 지배 중단을 빼고({@link #continuingIncomeControlling}과 같은 갈래), 배분 0이면 지배 계속영업 그대로, 계속영업 몫으로 지었으면 그 값,
     * 못 정했으면 {@code null}이다. 그 밖 행은 {@link #continuingIncomeControlling} 그대로다.
     *
     * <p>원장의 주당순이익(ASC 260 · IAS 33)은 지배주주 순이익에서 우선주 배당 · 참가증권 · GP 배분을 뺀 몫이다 — 지배 NI를 보통주 수로
     * 나누면 그 몫이 얹힌다(TPG 33% · MCHP 48% · 은행 5~9% · HPE · APG는 부호가 뒤집힌다).
     */
    public BigDecimal commonContinuingIncome() {
        if (common == null || common.sameAsControlling()) {
            return continuingIncomeControlling();
        }
        if (common.value() == null) {
            return null;
        }
        return common.continuing() ? common.value() : continuingOf(common.value());
    }

    /**
     * <b>보통주 귀속 순이익(중단 포함)</b> — 배당성향의 분모(미국 배당 총액은 보통주 DPS × 보통주 수다). 이 규칙으로 안 읽은 행은
     * {@code null}을 내고 호출부가 지배 NI 갈래로 간다.
     */
    public BigDecimal commonNetIncome() {
        if (common == null) {
            return null;
        }
        if (common.sameAsControlling()) {
            return netIncomeControlling;
        }
        if (common.value() == null) {
            return null;
        }
        if (!common.continuing()) {
            return common.value();
        }
        if (discontinuedRule == null) {
            return common.value();
        }
        if (discontinuedIncomeControlling != null) {
            return common.value().add(discontinuedIncomeControlling);
        }
        return discontinuedIncome != null && discontinuedIncome.signum() == 0 ? common.value() : null;
    }

    /**
     * <b>배당성향의 분모</b> — 국내 관문 켬 행은 판정이 정한 분모(사용자 결정 「지배 NI − 신종자본증권 몫」 · ADR-0192 · 못 정했으면
     * {@code null}), 미국 관문 켬 행은 {@link #commonNetIncome}(보통주 몫 · 중단 포함). 이 규칙으로 안 읽은 행은 {@code null}이고 호출부가
     * 지배 NI 갈래로 간다.
     */
    public BigDecimal dividendIncome() {
        if (common == null) {
            return null;
        }
        return common.payoutDeclared() ? common.payoutBase() : commonNetIncome();
    }

    /** 이 행이 보통주 귀속 규칙으로 읽혔는가(미국 · 국내 관문 켬). */
    public boolean readsCommonIncome() {
        return common != null;
    }

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
