package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ROE}의 분모가 <b>분자와 같은 기저</b>인가.
 *
 * <p>미국은 분자가 {@code NetIncomeLoss}(지배주주 귀속)인데 {@code total_equity}의 첫 후보가
 * {@code …IncludingPortionAttributableToNoncontrollingInterest}(비지배 포함)라, 그대로 나누면
 * <b>남의 지분이 분모에만 들어간 수</b>가 나온다. 실측(2026-09-15 · 표본 8):
 * <b>{@code LNG} 59.13% → 109.74%(+50.61%p)</b> · <b>{@code KKR} 3.06% → 7.63%(+4.57%p)</b>.
 * <b>{@code ROE}는 {@code 버핏 우량주}의 필수 게이트다.</b>
 */
class RoeEquityBasisTest {

    @Test
    @DisplayName("지배주주 자본이 있으면 그것으로 나눈다 — 비지배지분이 분모에만 들어가면 안 된다")
    void usesControllingEquityWhenPresent() {
        // KKR FY2025 실측: 순이익 2,370,463 · 비지배 포함 78,921,669 · 지배주주 30,902,561.
        FinancialFigures f = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                .netIncome(new BigDecimal("2370463"))
                .totalEquity(new BigDecimal("78921669"))
                .totalEquityControlling(new BigDecimal("30902561"))
                .build();

        assertThat(FinancialMath.equityMatchingNetIncome(f))
                .isEqualByComparingTo("30902561");
        assertThat(FinancialMath.percentage(f.netIncome(), FinancialMath.equityMatchingNetIncome(f)))
                .as("3.00%가 아니라 7.67%다")
                .isCloseTo(new BigDecimal("7.67"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(FinancialMath.percentage(f.netIncome(), f.totalEquity()))
                .as("총자본으로 나누면 3.00%가 된다 — 이 시험이 막는 수다")
                .isCloseTo(new BigDecimal("3.00"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
    }

    @Test
    @DisplayName("지배주주 자본이 없으면 총자본으로 물러난다 — 국내는 분자도 비지배 포함이라 그것이 맞다")
    void fallsBackToTotalEquity() {
        FinancialFigures f = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                .netIncome(new BigDecimal("100"))
                .totalEquity(new BigDecimal("1000"))
                .build();

        assertThat(FinancialMath.equityMatchingNetIncome(f)).isEqualByComparingTo("1000");
    }
}
