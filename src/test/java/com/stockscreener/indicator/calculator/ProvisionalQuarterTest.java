package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.ProvisionalRelease;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 막힌 봉의 잠정 분기 행(국내 잠정 관문 켬 · {@link ReportingBasis#provisionalQuarter} · {@link FinancialMath#financialsAt}) — 갈래마다
 * 한 경우. 정식 분기 넷(2025 Q3 ~ 2026 Q2)이 보이고 10-08에 3분기 잠정실적이 나온 종목이다.
 */
class ProvisionalQuarterTest {

    private static final BigDecimal TRILLION = new BigDecimal("1000000000000");
    private static final LocalDate RELEASE = LocalDate.of(2026, 10, 8);

    /** 정식 분기 — 매출(조원) · 영업이익 · 잔액을 싣는다. */
    private static FinancialFigures formal(LocalDate start, LocalDate end, String revenue, String operating) {
        return FinancialFiguresFixture.quarter(end).periodStart(start).fiscalPeriod(
                        FinancialFigures.FiscalPeriod.valueOf("Q" + end.getMonthValue() / 3))
                .revenue(new BigDecimal(revenue).multiply(TRILLION)).operatingIncome(new BigDecimal(operating).multiply(TRILLION))
                .netIncome(new BigDecimal("10").multiply(TRILLION)).totalAssets(new BigDecimal("500").multiply(TRILLION))
                .totalEquity(new BigDecimal("400").multiply(TRILLION)).sharesOutstanding(new BigDecimal("5900000000"))
                .build();
    }

    private static List<FinancialFigures> formalQuarters() {
        // 판 이력 순서(기말 내림차순) — 리더가 그렇게 준다.
        return List.of(
                formal(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "171.50", "89.49"),
                formal(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), "133.88", "57.24"),
                formal(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), "93.84", "20.07"),
                formal(LocalDate.of(2025, 7, 1), LocalDate.of(2025, 9, 30), "86.06", "12.17"));
    }

    /** 3분기 잠정 — 당해 매출 195.25 · 누계 = 1·2분기 정식 + 당해(500.63) · 영업이익 107.44 / 254.17. */
    private static ProvisionalRelease q3(String rceptNo, LocalDate filed, LocalDate visible, boolean basisMatches, String cumRevenue) {
        return new ProvisionalRelease(rceptNo, filed, visible, basisMatches, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), 1_000_000_000_000L,
                new ProvisionalRelease.Lines(new BigDecimal("195.25"), new BigDecimal("107.44"), null, null, null),
                new ProvisionalRelease.Lines(new BigDecimal(cumRevenue), new BigDecimal("254.17"), null, null, null));
    }

    private static IndicatorContext context(List<FinancialFigures> quarters, List<LocalDate> releases, List<ProvisionalRelease> provisional) {
        return new IndicatorContext(1L, List.of(), List.of(), quarters, Map.of()).withEarningsReleaseDates(releases)
                .withProvisionalReleases(provisional);
    }

    private static IndicatorContext context(List<FinancialFigures> annual, List<FinancialFigures> quarters, List<LocalDate> releases,
                                            List<ProvisionalRelease> provisional) {
        return new IndicatorContext(1L, List.of(), annual, quarters, Map.of()).withEarningsReleaseDates(releases)
                .withProvisionalReleases(provisional);
    }

    @Test
    @DisplayName("대조군: 잠정 본문이 없으면(관문 꺼짐) 막힌 봉은 지금처럼 재무를 비운다")
    void withoutProvisionalTheBarIsEmpty() {
        IndicatorContext at = FinancialMath.financialsAt(context(formalQuarters(), List.of(RELEASE), List.of()), RELEASE);

        assertThat(at.quarterlyFigures()).isEmpty();
        assertThat(at.annualFigures()).isEmpty();
    }

    @Test
    @DisplayName("자체 검사를 통과한 잠정이 보이면 그 분기의 손익 행을 더한다 — 매출 · 영업이익만 · 잔액 · 주식수 · 보통주 몫은 비고 연간은 비운다")
    void provisionalQuarterIsAdded() {
        IndicatorContext at = FinancialMath.financialsAt(
                context(formalQuarters(), List.of(RELEASE), List.of(q3("r1", RELEASE, RELEASE, true, "500.63"))), RELEASE);

        FinancialFigures row = at.quarterlyFigures().get(0);
        assertThat(row.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(row.fiscalPeriod()).isEqualTo(FinancialFigures.FiscalPeriod.Q3);
        assertThat(row.revenue()).isEqualByComparingTo(new BigDecimal("195.25").multiply(TRILLION));
        assertThat(row.operatingIncome()).isEqualByComparingTo(new BigDecimal("107.44").multiply(TRILLION));
        assertThat(row.netIncome()).isNull();
        assertThat(row.totalAssets()).isNull();
        assertThat(row.sharesOutstanding()).isNull();
        assertThat(row.commonContinuingIncome()).as("보통주 몫 못 정함 — 주당 지표는 정식까지 빔").isNull();
        assertThat(at.annualFigures()).isEmpty();
        FinancialMath.PeriodSeries series = FinancialMath.seriesOf(at);
        assertThat(series.basis()).isEqualTo(FinancialMath.PeriodBasis.QUARTERLY);
        assertThat(series.ttm(FinancialFigures::revenue, 0)).isEqualByComparingTo(
                new BigDecimal("195.25").add(new BigDecimal("171.50")).add(new BigDecimal("133.88")).add(new BigDecimal("93.84"))
                        .multiply(TRILLION));
        assertThat(series.latestPoint().totalAssets()).as("잔액 지표의 기준 판이 잠정 행이라 빈다").isNull();
    }

    @Test
    @DisplayName("당해 ≠ 누계 − 정식 앞 분기 합이면(정정 표 열 오독 꼴) 그 항목을 못 읽음으로 두고, 한 항목도 안 서면 전체 막기다")
    void failedSelfCheckBlocks() {
        ProvisionalRelease misread = new ProvisionalRelease("r1", RELEASE, RELEASE, true, LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), 1_000_000_000_000L,
                new ProvisionalRelease.Lines(new BigDecimal("195.00"), new BigDecimal("107.40"), null, null, null),
                new ProvisionalRelease.Lines(new BigDecimal("195.25"), new BigDecimal("107.44"), null, null, null));

        assertThat(FinancialMath.financialsAt(context(formalQuarters(), List.of(RELEASE), List.of(misread)), RELEASE)
                .quarterlyFigures()).isEmpty();
    }

    @Test
    @DisplayName("반올림 폭 안의 차이는 통과한다(조원 둘째 자리 · 당해와 누계의 반 칸씩)")
    void roundingWithinTheLastDigitPasses() {
        IndicatorContext at = FinancialMath.financialsAt(
                context(formalQuarters(), List.of(RELEASE), List.of(q3("r1", RELEASE, RELEASE, true, "500.64"))), RELEASE);

        assertThat(at.quarterlyFigures().get(0).revenue()).isNotNull();
    }

    @Test
    @DisplayName("양식 기준이 정식 판과 다르거나 정식 판이 원화가 아니면 쓰지 않는다")
    void basisMismatchBlocks() {
        assertThat(FinancialMath.financialsAt(
                context(formalQuarters(), List.of(RELEASE), List.of(q3("r1", RELEASE, RELEASE, false, "500.63"))), RELEASE)
                .quarterlyFigures()).isEmpty();
    }

    @Test
    @DisplayName("장 마감 뒤 잠정 — 막기 시작(발표 날)과 보이기(다음 봉)가 갈리는 하루는 전체 막기, 다음 봉부터 잠정 행")
    void visibilityGapDayIsBlocked() {
        LocalDate next = RELEASE.plusDays(1);
        IndicatorContext context = context(formalQuarters(), List.of(RELEASE), List.of(q3("r1", RELEASE, next, true, "500.63")));

        assertThat(FinancialMath.financialsAt(context, RELEASE).quarterlyFigures()).isEmpty();
        assertThat(FinancialMath.financialsAt(context, next).quarterlyFigures().get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(context.financialPublicationDates()).as("보이는 날이 구간 경계다").contains(next);
    }

    @Test
    @DisplayName("같은 분기 정식 판이 보이면 정식이 이긴다(막기가 풀린다)")
    void formalWins() {
        FinancialFigures formalQ3 = FinancialFiguresFixture.from(formal(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "195.30",
                "107.50")).publishedAt(LocalDate.of(2026, 11, 13)).build();
        List<FinancialFigures> quarters = new java.util.ArrayList<>(List.of(formalQ3));
        quarters.addAll(formalQuarters());
        IndicatorContext at = FinancialMath.financialsAt(
                context(quarters, List.of(RELEASE), List.of(q3("r1", RELEASE, RELEASE, true, "500.63"))), LocalDate.of(2026, 11, 13));

        assertThat(at.quarterlyFigures().get(0).revenue()).isEqualByComparingTo(new BigDecimal("195.30").multiply(TRILLION));
        assertThat(at.quarterlyFigures().get(0).totalAssets()).as("정식 판이다").isNotNull();
    }

    @Test
    @DisplayName("정정 잠정이 보이면 항목마다 늦은 판 — 첫 판이 매출만, 정정이 순이익까지면 매출 · 순이익 모두 정정 판")
    void laterVintageWinsPerItem() {
        ProvisionalRelease first = q3("r1", RELEASE, RELEASE, true, "500.63");
        LocalDate corrected = LocalDate.of(2026, 10, 29);
        ProvisionalRelease correction = new ProvisionalRelease("r2", corrected, corrected, true, LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), 100_000_000L,
                new ProvisionalRelease.Lines(new BigDecimal("1952600"), null, null, new BigDecimal("120000"), null),
                new ProvisionalRelease.Lines(new BigDecimal("5006400"), null, null, new BigDecimal("320000"), null));
        // 정식 1 · 2분기 순이익이 각 10조(=100,000억)이라 누계 순이익 320,000억 − 200,000억 = 당해 120,000억.
        IndicatorContext at = FinancialMath.financialsAt(
                context(formalQuarters(), List.of(RELEASE, corrected), List.of(first, correction)), corrected);

        FinancialFigures row = at.quarterlyFigures().get(0);
        assertThat(row.revenue()).isEqualByComparingTo(new BigDecimal("195.26").multiply(TRILLION));
        assertThat(row.netIncome()).isEqualByComparingTo(new BigDecimal("12").multiply(TRILLION));
        assertThat(row.operatingIncome()).as("정정 판에 없는 항목은 앞 판").isEqualByComparingTo(new BigDecimal("107.44").multiply(TRILLION));
        assertThat(row.publishedAt()).isEqualTo(corrected);
    }

    @Test
    @DisplayName("4분기 — 결산 공시(I001)뿐이면 결산 − 정식 1~3분기다")
    void fourthQuarterFromTheYear() {
        List<FinancialFigures> quarters = List.of(
                formal(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "195.25", "107.44"),
                formal(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "171.50", "89.49"),
                formal(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), "133.88", "57.24"),
                formal(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), "93.84", "20.07"));
        LocalDate filed = LocalDate.of(2027, 1, 27);
        ProvisionalRelease year = new ProvisionalRelease("r9", filed, filed, true, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 1_000_000_000_000L,
                new ProvisionalRelease.Lines(new BigDecimal("700.63"), null, null, null, null),
                new ProvisionalRelease.Lines(new BigDecimal("700.63"), null, null, null, null));
        FinancialFigures fy2025 = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                .revenue(new BigDecimal("600").multiply(TRILLION)).totalAssets(new BigDecimal("500").multiply(TRILLION))
                .publishedAt(LocalDate.of(2026, 3, 10)).build();
        IndicatorContext at = FinancialMath.financialsAt(context(List.of(fy2025), quarters, List.of(filed), List.of(year)), filed);

        FinancialFigures row = at.quarterlyFigures().get(0);
        assertThat(row.fiscalPeriod()).isEqualTo(FinancialFigures.FiscalPeriod.Q4);
        assertThat(row.revenue()).isEqualByComparingTo(new BigDecimal("200.00").multiply(TRILLION));
        // 결산 공시가 연간 손익을 준다 — 연간 판은 잠정 FY 행 + 앞선 해의 정식 판이고, 연간 매출 성장률이 선다. 잔액은 없다.
        assertThat(at.annualFigures()).hasSize(2);
        FinancialFigures fy = at.annualFigures().get(0);
        assertThat(fy.fiscalPeriod()).isEqualTo(FinancialFigures.FiscalPeriod.FY);
        assertThat(fy.revenue()).isEqualByComparingTo(new BigDecimal("700.63").multiply(TRILLION));
        assertThat(fy.totalAssets()).isNull();
        assertThat(fy.commonContinuingIncome()).isNull();
        assertThat(YearOverYear.growth(at.annualFigures(), FinancialFigures::revenue)).isNotNull();
    }

    @Test
    @DisplayName("1~3분기 잠정은 연간 값을 안 준다 — 앞선 해의 정식 연간 판이 있어도 연간 판은 비운다(지금 막기와 같다)")
    void notFourthQuarterKeepsAnnualEmpty() {
        FinancialFigures fy2025 = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                .revenue(new BigDecimal("400").multiply(TRILLION)).publishedAt(LocalDate.of(2026, 3, 10)).build();
        IndicatorContext at = FinancialMath.financialsAt(
                context(List.of(fy2025), formalQuarters(), List.of(RELEASE), List.of(q3("r1", RELEASE, RELEASE, true, "500.63"))), RELEASE);

        assertThat(at.quarterlyFigures().get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(at.annualFigures()).isEmpty();
    }

    @Test
    @DisplayName("다음 분기 공시가 아니면(기간이 기준 다음 분기가 아님) 쓰지 않는다 — 연속이 끊긴 종목")
    void notTheNextQuarterBlocks() {
        List<FinancialFigures> gap = formalQuarters().subList(1, 4);
        assertThat(FinancialMath.financialsAt(
                context(gap, List.of(RELEASE), List.of(q3("r1", RELEASE, RELEASE, true, "500.63"))), RELEASE)
                .quarterlyFigures()).isEmpty();
    }

    @Test
    @DisplayName("중단영업 판정 규칙은 직전 정식 분기의 것을 잇는다 — 규칙이 있으면 중단 몫 모름(계속영업 손익이 빈다)")
    void discontinuedRuleIsCarried() {
        List<FinancialFigures> quarters = new java.util.ArrayList<>(formalQuarters());
        quarters.set(0, FinancialFiguresFixture.from(quarters.get(0)).discontinued(BigDecimal.ZERO, BigDecimal.ZERO).build());
        ProvisionalRelease withIncome = new ProvisionalRelease("r1", RELEASE, RELEASE, true, LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), 1_000_000_000_000L,
                new ProvisionalRelease.Lines(null, null, null, new BigDecimal("12.00"), null),
                new ProvisionalRelease.Lines(null, null, null, new BigDecimal("32.00"), null));
        FinancialFigures row = FinancialMath.financialsAt(context(quarters, List.of(RELEASE), List.of(withIncome)), RELEASE)
                .quarterlyFigures().get(0);

        assertThat(row.netIncome()).isEqualByComparingTo(new BigDecimal("12").multiply(TRILLION));
        assertThat(row.discontinuedRule()).isEqualTo(quarters.get(0).discontinuedRule());
        assertThat(row.continuingIncome()).as("규칙이 있는데 잠정은 중단 몫을 안 준다").isNull();
    }
}
