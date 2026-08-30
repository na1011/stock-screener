package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 테스트용 {@link FinancialFigures} 빌더.
 *
 * <p>레코드의 위치 인자를 테스트마다 직접 쓰면 필드를 하나 추가할 때 모든 호출부가 깨지고,
 * 무엇보다 {@code null, null, netIncome, null, null}처럼 <b>어느 자리가 무엇인지 읽을 수
 * 없는</b> 코드가 된다. 그 상태에서는 인자 순서를 잘못 넣어도 컴파일이 통과한다.
 *
 * <p>기본값은 전부 {@code null}이다 — 각 테스트가 자기가 쓰는 항목만 채우게 해서,
 * 그 테스트가 실제로 무엇에 의존하는지 드러나게 한다.
 */
final class FinancialFiguresFixture {

    private int fiscalYear;
    private FinancialFigures.FiscalPeriod fiscalPeriod = FinancialFigures.FiscalPeriod.Q4;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate publishedAt;
    private String currencyCode = "KRW";
    private BigDecimal revenue;
    private BigDecimal operatingIncome;
    private BigDecimal netIncome;
    private BigDecimal totalAssets;
    private BigDecimal totalEquity;
    private BigDecimal totalLiabilities;
    private BigDecimal currentAssets;
    private BigDecimal currentLiabilities;
    private BigDecimal retainedEarnings;
    private BigDecimal operatingCashFlow;
    private BigDecimal sharesOutstanding;
    private BigDecimal eps;
    private BigDecimal bps;
    private BigDecimal costOfSales;
    private BigDecimal cashAndEquivalents;
    private BigDecimal capex;
    private BigDecimal inventories;
    private BigDecimal netIncomeControlling;

    private FinancialFiguresFixture() {
    }

    /** {@code periodEnd}로 끝나는 분기. 기간·공시일은 그로부터 유도한다. */
    static FinancialFiguresFixture quarter(LocalDate periodEnd) {
        FinancialFiguresFixture fixture = new FinancialFiguresFixture();
        fixture.fiscalYear = periodEnd.getYear();
        fixture.periodStart = periodEnd.minusMonths(3);
        fixture.periodEnd = periodEnd;
        fixture.publishedAt = periodEnd.plusMonths(2);
        return fixture;
    }

    /** {@code periodEnd}로 끝나는 연간(FY) 재무. */
    static FinancialFiguresFixture annual(LocalDate periodEnd) {
        FinancialFiguresFixture fixture = quarter(periodEnd);
        fixture.fiscalPeriod = FinancialFigures.FiscalPeriod.FY;
        fixture.periodStart = periodEnd.minusYears(1).plusDays(1);
        return fixture;
    }

    /** 기존 값을 복사해 일부만 바꾼다. */
    static FinancialFiguresFixture from(FinancialFigures source) {
        FinancialFiguresFixture fixture = new FinancialFiguresFixture();
        fixture.fiscalYear = source.fiscalYear();
        fixture.fiscalPeriod = source.fiscalPeriod();
        fixture.periodStart = source.periodStart();
        fixture.periodEnd = source.periodEnd();
        fixture.publishedAt = source.publishedAt();
        fixture.currencyCode = source.currencyCode();
        fixture.revenue = source.revenue();
        fixture.operatingIncome = source.operatingIncome();
        fixture.netIncome = source.netIncome();
        fixture.totalAssets = source.totalAssets();
        fixture.totalEquity = source.totalEquity();
        fixture.totalLiabilities = source.totalLiabilities();
        fixture.currentAssets = source.currentAssets();
        fixture.currentLiabilities = source.currentLiabilities();
        fixture.retainedEarnings = source.retainedEarnings();
        fixture.operatingCashFlow = source.operatingCashFlow();
        fixture.sharesOutstanding = source.sharesOutstanding();
        fixture.eps = source.eps();
        fixture.bps = source.bps();
        fixture.costOfSales = source.costOfSales();
        fixture.cashAndEquivalents = source.cashAndEquivalents();
        fixture.capex = source.capex();
        // MUST: 컴포넌트를 더하면 여기에도 더한다. 빠뜨리면 from()을 거친 픽스처만 그 값을
        // 조용히 잃고, 그 테스트는 「값이 없을 때」를 재는 것으로 바뀌는데 이름은 그대로다.
        // inventories가 실제로 그렇게 빠져 있었다.
        fixture.inventories = source.inventories();
        fixture.netIncomeControlling = source.netIncomeControlling();
        return fixture;
    }

    FinancialFiguresFixture revenue(BigDecimal value) {
        this.revenue = value;
        return this;
    }

    FinancialFiguresFixture operatingIncome(BigDecimal value) {
        this.operatingIncome = value;
        return this;
    }

    FinancialFiguresFixture netIncome(BigDecimal value) {
        this.netIncome = value;
        return this;
    }

    FinancialFiguresFixture totalAssets(BigDecimal value) {
        this.totalAssets = value;
        return this;
    }

    FinancialFiguresFixture totalEquity(BigDecimal value) {
        this.totalEquity = value;
        return this;
    }

    FinancialFiguresFixture totalLiabilities(BigDecimal value) {
        this.totalLiabilities = value;
        return this;
    }

    FinancialFiguresFixture currentAssets(BigDecimal value) {
        this.currentAssets = value;
        return this;
    }

    FinancialFiguresFixture currentLiabilities(BigDecimal value) {
        this.currentLiabilities = value;
        return this;
    }

    FinancialFiguresFixture retainedEarnings(BigDecimal value) {
        this.retainedEarnings = value;
        return this;
    }

    FinancialFiguresFixture operatingCashFlow(BigDecimal value) {
        this.operatingCashFlow = value;
        return this;
    }

    FinancialFiguresFixture sharesOutstanding(BigDecimal value) {
        this.sharesOutstanding = value;
        return this;
    }

    FinancialFiguresFixture costOfSales(BigDecimal value) {
        this.costOfSales = value;
        return this;
    }

    FinancialFiguresFixture capex(BigDecimal value) {
        this.capex = value;
        return this;
    }

    FinancialFiguresFixture cashAndEquivalents(BigDecimal value) {
        this.cashAndEquivalents = value;
        return this;
    }

    FinancialFiguresFixture inventories(BigDecimal value) {
        this.inventories = value;
        return this;
    }

    /**
     * 지배기업 소유주 귀속 순이익. <b>기본값 null이 의도다</b> — 비지배지분이 없는 회사에서
     * 소스가 이 행을 아예 내지 않는 것이 정상이고, 그때 {@code netIncome}으로 폴백하는 것이
     * 옳다. 그 폴백을 타는 것이 대부분의 테스트가 재는 상황이다.
     */
    FinancialFiguresFixture netIncomeControlling(BigDecimal value) {
        this.netIncomeControlling = value;
        return this;
    }

    FinancialFigures build() {
        return new FinancialFigures(
                fiscalYear, fiscalPeriod, periodStart, periodEnd, publishedAt, currencyCode,
                revenue, operatingIncome, netIncome, totalAssets, totalEquity, totalLiabilities,
                currentAssets, currentLiabilities, retainedEarnings,
                operatingCashFlow, sharesOutstanding, eps, bps,
                costOfSales, cashAndEquivalents, capex, inventories, netIncomeControlling);
    }
}
