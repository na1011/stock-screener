package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 F1(ADR-0168) — 분사 뒤 봉의 가격은 자식이 빠진 회사다. 분사일 앞에 시작한 기간의 판은 자식을 품은 회사라 그 판을 가격과
 * 나누지 않는다. 재게재 판으로 가르지 않는다 — 같은 값 이어받기 판(HON FY2025 2026-10-22)이 「분사 뒤」로 통과해 거짓 PER을 냈다.
 *
 * <p>무너지는 방식이 조용하다 — 분사 뒤 가격을 자식 이익까지 품은 이익으로 나눠 거짓 저PER이 나간다.
 */
class SpinOffValuationBasisTest {

    private static final LocalDate SPIN_OFF = LocalDate.of(2025, 11, 3);
    private static final LocalDate BEFORE = LocalDate.of(2025, 10, 1);
    private static final LocalDate GAP = LocalDate.of(2025, 12, 1);
    /** 2026년 1분기(분사 뒤에 시작한 첫 기간) 공시 뒤. */
    private static final LocalDate FIRST_POST_BALANCE = LocalDate.of(2026, 6, 1);
    /** 2026년 1~3분기가 공시된 뒤 · 4분기 전 — 2025년 4분기를 쓰면 TTM이 서는 봉. */
    private static final LocalDate THREE_POST = LocalDate.of(2026, 12, 1);
    /** 2026년 네 분기가 다 공시된 뒤. */
    private static final LocalDate FULL_POST = LocalDate.of(2027, 3, 1);

    private static FinancialFigures quarter(LocalDate end, LocalDate published, long netIncome) {
        // 픽스처 기본 분기는 Q4다 — 이 시험은 4분기 규칙을 재므로 기말 월로 분기를 정한다.
        FinancialFigures.FiscalPeriod period = FinancialFigures.FiscalPeriod.valueOf("Q" + (end.getMonthValue() + 2) / 3);
        return FinancialFiguresFixture.quarter(end).fiscalPeriod(period).publishedAt(published)
                .netIncome(BigDecimal.valueOf(netIncome))
                .sharesOutstanding(BigDecimal.valueOf(100))
                .totalEquity(BigDecimal.valueOf(2_000))
                .totalAssets(BigDecimal.valueOf(5_000))
                .totalLiabilities(BigDecimal.valueOf(3_000))
                .build();
    }

    /**
     * 2024년 3분기 ~ 2026년 4분기. 분사 앞에 시작한 분기 이익 250, 2026년 분기 이익 150. 2025년 4분기(분사를 품음)와 그 FY 10-K가
     * 2025년 1~3분기를 같은 날 다시 싣는다 — {@code recastValue}가 그 값이다(같은 값이면 이어받기 판).
     */
    private static IndicatorContext context(long recastValue) {
        return context(recastValue, SPIN_OFF);
    }

    private static IndicatorContext context(long recastValue, LocalDate spinOff) {
        LocalDate tenK = LocalDate.of(2026, 2, 17);
        List<FinancialFigures> quarters = new ArrayList<>();
        quarters.add(quarter(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 2, 15), 150));
        quarters.add(quarter(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 11, 1), 150));
        quarters.add(quarter(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 8, 1), 150));
        quarters.add(quarter(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 1), 150));
        quarters.add(quarter(LocalDate.of(2025, 12, 31), tenK, 150));
        quarters.add(quarter(LocalDate.of(2025, 9, 30), tenK, recastValue));
        quarters.add(quarter(LocalDate.of(2025, 9, 30), LocalDate.of(2025, 11, 6), 250));
        quarters.add(quarter(LocalDate.of(2025, 6, 30), tenK, recastValue));
        quarters.add(quarter(LocalDate.of(2025, 6, 30), LocalDate.of(2025, 8, 1), 250));
        quarters.add(quarter(LocalDate.of(2025, 3, 31), tenK, recastValue));
        quarters.add(quarter(LocalDate.of(2025, 3, 31), LocalDate.of(2025, 5, 1), 250));
        quarters.add(quarter(LocalDate.of(2024, 12, 31), LocalDate.of(2025, 2, 14), 250));
        quarters.add(quarter(LocalDate.of(2024, 9, 30), LocalDate.of(2024, 11, 1), 250));
        List<PriceBar> bars = new ArrayList<>();
        for (LocalDate date : List.of(BEFORE, GAP, FIRST_POST_BALANCE, THREE_POST, FULL_POST)) {
            BigDecimal price = BigDecimal.valueOf(120);
            bars.add(new PriceBar(date, price, price, price, price, price, 1_000L, null, null));
        }
        return new IndicatorContext(1L, bars, List.of(), quarters, Map.of())
                .withSpinOffDates(List.of(spinOff));
    }

    private static List<LocalDate> dates(List<IndicatorPoint> points) {
        return points.stream().map(IndicatorPoint::tradeDate).toList();
    }

    @Test
    @DisplayName("분사 뒤 PER은 분사 뒤에 시작한 네 분기가 찰 때까지 없다 — 재게재 판이 있어도, 같은 값 이어받기 판이어도")
    void perWaitsForFourPostSpinOffQuarters() {
        for (long recast : List.of(150L, 250L)) {
            List<IndicatorPoint> per = new PerCalculator().calculate(context(recast));
            assertThat(dates(per)).as("재게재 값 %d", recast).containsExactly(BEFORE, FULL_POST);
            // 분사 뒤 TTM = 150 × 4 = 600 → EPS 6 → PER 20.
            assertThat(per.getLast().value()).isEqualByComparingTo("20");
        }
    }

    @Test
    @DisplayName("잔액만 읽는 PBR은 분사 뒤에 시작한 첫 기간이 공시되면 다시 선다")
    void pbrReturnsWithTheFirstPostSpinOffPeriod() {
        assertThat(dates(new PbrCalculator().calculate(context(250))))
                .containsExactly(BEFORE, FIRST_POST_BALANCE, THREE_POST, FULL_POST);
    }

    @Test
    @DisplayName("분사 뒤 재무 대기는 분사 뒤에 시작한 분기가 넷 미만일 때다")
    void awaitingFlagFollowsTheSameRule() {
        IndicatorContext context = context(250);
        assertThat(context.visibleAt(BEFORE).awaitingPostSpinOffFinancials(BEFORE)).isFalse();
        assertThat(context.visibleAt(FIRST_POST_BALANCE).awaitingPostSpinOffFinancials(FIRST_POST_BALANCE)).isTrue();
        assertThat(context.visibleAt(FULL_POST).awaitingPostSpinOffFinancials(FULL_POST)).isFalse();
    }

    @Test
    @DisplayName("4분기의 입력 기간은 그 FY다 — 분사 뒤에 시작한 4분기라도 FY가 분사 앞에 시작했으면 쓰지 않는다")
    void fourthQuarterSpansItsFiscalYear() {
        // 분사 2025-08-01: 2025년 4분기(10-01 시작)는 분사 뒤지만 FY − 1~3분기로 지은 값이라 분사 앞 분기가 섞인다.
        IndicatorContext context = context(250, LocalDate.of(2025, 8, 1));
        // 이 분사에서는 BEFORE(10-01) 봉도 분사 뒤다.
        assertThat(dates(new PerCalculator().calculate(context))).containsExactly(FULL_POST);
        assertThat(dates(new PbrCalculator().calculate(context))).containsExactly(FIRST_POST_BALANCE, THREE_POST, FULL_POST);
    }

    @Test
    @DisplayName("「분사 앞」의 경계는 효력일 직전 거래일 — 그 사이에 시작한 기간은 분사 뒤다")
    void boundaryIsThePreviousTradingDay() {
        // 효력일 2026-01-05(월) · 직전 거래일 2025-12-01(이 시험의 봉) — 2026년 1분기(01-01 시작)는 분사 뒤다.
        IndicatorContext context = context(250, LocalDate.of(2026, 1, 5));
        assertThat(dates(new PbrCalculator().calculate(context))).containsExactly(BEFORE, GAP, FIRST_POST_BALANCE, THREE_POST, FULL_POST);
    }
}
