package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;
import com.stockscreener.indicator.ShareBasis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예탁증권 줄 행(ADR-0183)의 기저 배수가 지표 쪽에서도 곱해지는가 — 순위는 상태와 무관하게 배수를 곱하므로 지표가 한 상태라도
 * 안 곱하면 둘이 k배로 갈린다(2026-10-22 B 계획 반증: 지표는 SHIFTED만 곱했다).
 */
class DepositaryBasisTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 28);
    private static final BigDecimal CLOSE = new BigDecimal("30");

    @Test
    @DisplayName("DEPOSITARY 행은 보통주에 1/k를 곱해 ADS 시세와 맞댄다(TAL 꼴 · k = 1/3 → ×3)")
    void marketCapUsesTheDepositaryMultiple() {
        List<IndicatorPoint> points = new MarketCapCalculator().calculate(context("DEPOSITARY", new BigDecimal("3")));

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).as("종가 30 × 보통주 1,000,000 × 3").isEqualByComparingTo("90000000");
    }

    @Test
    @DisplayName("배수가 있어도 상태가 OK면 안 곱한다 — 곱셈은 상태가 정한다")
    void okIgnoresTheMultiple() {
        List<IndicatorPoint> points = new MarketCapCalculator().calculate(context(null, new BigDecimal("3")));

        assertThat(points.get(0).value()).isEqualByComparingTo("30000000");
    }

    @Test
    @DisplayName("모르는 상태 문자열은 OK로 접지 않고 던진다")
    void unknownStatusFailsClosed() {
        assertThat(ShareBasis.of(null)).isEqualTo(ShareBasis.OK);
        assertThat(ShareBasis.of("DEPOSITARY").multiplies()).isTrue();
        assertThat(ShareBasis.of("SHIFTED").multiplies()).isTrue();
        assertThat(ShareBasis.of("UNRESOLVED").multiplies()).isFalse();
        assertThatThrownBy(() -> ShareBasis.of("DEPOSITORY")).isInstanceOf(IllegalStateException.class);
    }

    private static IndicatorContext context(String status, BigDecimal multiple) {
        PriceBar bar = new PriceBar(DAY, CLOSE, CLOSE, CLOSE, CLOSE, CLOSE, 1000L, null, null);
        FinancialFigures quarter = FinancialFiguresFixture.quarter(LocalDate.of(2026, 6, 30))
                .publishedAt(LocalDate.of(2026, 8, 5))
                .sharesOutstanding(new BigDecimal("1000000"))
                .shareBasis(status, multiple)
                .build();
        return new IndicatorContext(1L, List.of(bar), List.of(), List.of(quarter), Map.of());
    }
}
