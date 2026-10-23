package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 이익력 지표의 분자가 <b>계속영업</b>인지 고정한다(F2b · ADR-0184 · 탐색 {@code F2B-PLAN-1023.md}).
 *
 * <p>무너지는 방식이 조용하다 — 중단영업(매각 차익)이 섞여도 PER은 그럴듯한 값을 낸다. PAA는 Q2'26 순이익 1,830M 중
 * 1,649M이 중단영업이라 PER 6.05로 화면 #1 문을 통과했다.
 */
class ContinuingIncomeTest {

    private final PerCalculator per = new PerCalculator();
    private final EpsTtmCalculator epsTtm = new EpsTtmCalculator();
    private final RoeCalculator roe = new RoeCalculator();
    private final RoaCalculator roa = new RoaCalculator();
    private final NetMarginCalculator netMargin = new NetMarginCalculator();
    private final AccrualRatioCalculator accrual = new AccrualRatioCalculator();

    @Test
    @DisplayName("중단영업을 안 읽은 행(null)은 지금과 같은 값이다 — 관문 꺼짐 · 관문 전 행")
    void unreadRowsBehaveAsBefore() {
        IndicatorContext context = context(quarters(null, null), "150");

        // 지배주주 분기 200 × 4 = 800 · 주식수 100 → EPS 8 · PER 18.75.
        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("8");
        assertThat(per.calculate(context).get(0).value()).isEqualByComparingTo("18.75");
    }

    @Test
    @DisplayName("지배 중단영업을 빼서 PER · EPS를 낸다 — 매각 차익이 저PER을 만들지 않는다")
    void subtractsControllingDiscontinued() {
        // 최신 분기에만 중단영업(연결 120 · 지배 100). 지배 계속영업 = 800 − 100 = 700 → EPS 7 · PER 150/7.
        List<FinancialFigures> quarters = quarters("0", "0");
        quarters.set(0, FinancialFiguresFixture.from(quarters.get(0))
                .discontinued(new BigDecimal("120"), new BigDecimal("100")).build());
        IndicatorContext context = context(quarters, "150");

        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("7");
        assertThat(per.calculate(context).get(0).value()).isCloseTo(new BigDecimal("21.428571"), within(new BigDecimal("0.0001")));
    }

    @Test
    @DisplayName("지배 몫을 원장이 안 정해 주면 PER · EPS는 미표시다 — 연결 계속영업으로도 지금 값으로도 떨어지지 않는다")
    void unresolvedControllingHidesPerShareIndicators() {
        List<FinancialFigures> quarters = quarters("0", "0");
        quarters.set(2, FinancialFiguresFixture.from(quarters.get(2))
                .discontinued(new BigDecimal("-30"), null).build());
        IndicatorContext context = context(quarters, "150");

        assertThat(epsTtm.calculate(context)).as("연결 계속영업(1030)이나 중단 포함(800)이 아니다").isEmpty();
        assertThat(per.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("지배주주 순이익 자체가 없는 창은 기존처럼 연결로 가되 연결 계속영업이다")
    void consolidatedFallbackUsesContinuing() {
        List<FinancialFigures> quarters = new ArrayList<>();
        for (FinancialFigures q : quarters("0", null)) {
            quarters.add(FinancialFiguresFixture.from(q).netIncomeControlling(null).build());
        }
        quarters.set(0, FinancialFiguresFixture.from(quarters.get(0))
                .discontinued(new BigDecimal("200"), null).build());
        IndicatorContext context = context(quarters, "150");

        // 연결 250 × 4 = 1000 − 중단 200 = 800 → EPS 8.
        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("ROE는 짝을 지킨다 — 지배 몫 미정이면 연결 계속영업 ↔ 총자본")
    void roeKeepsThePairWhenControllingIsUnresolved() {
        List<FinancialFigures> quarters = quarters("0", "0");
        quarters.set(1, FinancialFiguresFixture.from(quarters.get(1))
                .discontinued(new BigDecimal("100"), null).build());
        IndicatorContext context = context(quarters, "150");

        // 연결 계속영업 = 1000 − 100 = 900 · 총자본 2000 → 45%. 지배 분자(700?)를 총자본에 얹지 않는다.
        assertThat(roe.calculate(context).get(0).value()).isEqualByComparingTo("45");
    }

    @Test
    @DisplayName("ROA · 순이익률은 연결 계속영업, 발생액은 합계 그대로다")
    void consolidatedIndicatorsSplitByBasis() {
        List<FinancialFigures> quarters = quarters("0", "0");
        quarters.set(0, FinancialFiguresFixture.from(quarters.get(0))
                .discontinued(new BigDecimal("400"), new BigDecimal("300")).build());
        IndicatorContext context = context(quarters, "150");
        IndicatorContext withoutDiscontinued = context(quarters("0", "0"), "150");

        // 연결 계속영업 600 / 총자산 4000 = 15% · 매출 2000 → 30%.
        assertThat(roa.calculate(context).get(0).value()).isEqualByComparingTo("15");
        assertThat(netMargin.calculate(context).get(0).value()).isEqualByComparingTo("30");
        assertThat(accrual.calculate(context)).as("영업현금흐름이 중단 포함 합계라 짝 — 분자를 안 바꾼다")
                .isEqualTo(accrual.calculate(withoutDiscontinued));
    }

    @Test
    @DisplayName("유효세율의 부호 판정 · 세전 유도는 계속영업 순이익으로 한다")
    void taxUsesContinuingIncome() {
        // 세전(계속) 100 · 법인세 −20(표기상 음수) · 연결 순이익 300(중단 220 포함).
        // 중단 포함으로 보면 세전 − 순이익 = −200 < 0이라 「환입」으로 읽어 세율이 음수가 된다.
        FinancialFigures annual = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                .netIncome(new BigDecimal("300"))
                .incomeTaxExpense(new BigDecimal("-20"))
                .profitBeforeTax(new BigDecimal("100"))
                .discontinued(new BigDecimal("220"), new BigDecimal("220"))
                .build();

        assertThat(FinancialMath.effectiveTaxRateOf(annual)).isEqualByComparingTo("20");
        assertThat(FinancialMath.pretaxIncomeOf(FinancialFiguresFixture.from(annual).profitBeforeTax(null).build(),
                new BigDecimal("20"))).as("세전 유도 = 계속영업 80 + 법인세 20").isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("읽었는데 연결 중단영업을 모르는 행(재분류된 해의 Q4)은 연결 · 지배 계속영업이 둘 다 미정이다 — 안 읽은 행과 다르다")
    void readButUnknownIsNotUnread() {
        FinancialFigures unread = FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31))
                .netIncome(new BigDecimal("250")).netIncomeControlling(new BigDecimal("200")).build();
        FinancialFigures unknown = FinancialFiguresFixture.from(unread).discontinued(null, null).build();

        assertThat(unread.continuingIncome()).isEqualByComparingTo("250");
        assertThat(unread.continuingIncomeControlling()).isEqualByComparingTo("200");
        assertThat(unknown.continuingIncome()).isNull();
        assertThat(unknown.continuingIncomeControlling()).isNull();
    }

    @Test
    @DisplayName("연결 중단영업이 0이면 지배 몫이 없어도 지배주주 순이익 그대로다")
    void zeroDiscontinuedNeedsNoControllingShare() {
        FinancialFigures row = FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31))
                .netIncome(new BigDecimal("250")).netIncomeControlling(new BigDecimal("200"))
                .discontinued(BigDecimal.ZERO, null).build();

        assertThat(row.continuingIncomeControlling()).isEqualByComparingTo("200");
        assertThat(row.continuingIncome()).isEqualByComparingTo("250");
    }

    private static IndicatorContext context(List<FinancialFigures> quarters, String close) {
        return new IndicatorContext(1L, List.of(priceBar(new BigDecimal(close))), List.of(), quarters, null);
    }

    private static PriceBar priceBar(BigDecimal close) {
        return new PriceBar(LocalDate.of(2026, 3, 2), close, close, close, close, close, 1000L, null, null);
    }

    /**
     * 연결 250 · 지배 200 · 주식수 100 · 매출 500 · 자산 4000 · 자본 2000(지배 1500) · 영업현금흐름 100인 네 분기.
     *
     * @param discontinued 네 분기 모두의 연결 중단영업(null이면 이 규칙으로 안 읽은 행)
     * @param controlling  네 분기 모두의 지배 중단영업
     */
    private static List<FinancialFigures> quarters(String discontinued, String controlling) {
        List<FinancialFigures> list = new ArrayList<>(4);
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < 4; i++) {
            list.add(FinancialFiguresFixture.quarter(end.minusMonths(3L * i))
                    .netIncome(new BigDecimal("250"))
                    .netIncomeControlling(new BigDecimal("200"))
                    .sharesOutstanding(BigDecimal.valueOf(100))
                    .revenue(new BigDecimal("500"))
                    .totalAssets(new BigDecimal("4000"))
                    .totalEquity(new BigDecimal("2000"))
                    .totalEquityControlling(new BigDecimal("1500"))
                    .operatingCashFlow(new BigDecimal("100"))
                    .build());
            if (discontinued != null) {
                list.set(i, FinancialFiguresFixture.from(list.get(i))
                        .discontinued(new BigDecimal(discontinued),
                                controlling == null ? null : new BigDecimal(controlling)).build());
            }
        }
        return list;
    }
}
