package com.stockscreener.indicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndicatorRegistryTest {

    @Test
    @DisplayName("코드가 중복되면 기동에 실패한다 — 새벽 배치에서 터지는 것보다 낫다")
    void rejectsDuplicateCodes() {
        assertThatThrownBy(() -> new IndicatorRegistry(List.of(
                stub("DUP", DataRequirement.priceBars(1)),
                stub("DUP", DataRequirement.priceBars(1)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("등록되지 않은 지표에 의존하면 기동에 실패한다")
    void rejectsUnknownDependency() {
        assertThatThrownBy(() -> new IndicatorRegistry(List.of(
                stub("CHILD", DataRequirement.derivedFrom(1, "NOT_REGISTERED")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_REGISTERED");
    }

    @Test
    @DisplayName("의존에 순환이 있으면 기동에 실패한다")
    void rejectsDependencyCycle() {
        assertThatThrownBy(() -> new IndicatorRegistry(List.of(
                stub("A", DataRequirement.derivedFrom(1, "B")),
                stub("B", DataRequirement.derivedFrom(1, "A")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환");
    }

    @Test
    @DisplayName("의존 지표가 먼저 오도록 정렬한다")
    void ordersDependenciesFirst() {
        IndicatorRegistry registry = new IndicatorRegistry(List.of(
                stub("MACD_V", DataRequirement.derivedFrom(10, "MACD", "ATR")),
                stub("MACD", DataRequirement.priceBars(35)),
                stub("ATR", DataRequirement.priceBars(14))));

        List<String> order = registry.executionOrder().stream().map(IndicatorCalculator::code).toList();

        assertThat(order).containsSubsequence("MACD", "MACD_V");
        assertThat(order).containsSubsequence("ATR", "MACD_V");
    }

    @Test
    @DisplayName("워밍업은 의존 그래프를 따라 유도한다 — 개발자는 자기 몫만 선언한다")
    void derivesEffectiveLookbackThroughDependencies() {
        IndicatorRegistry registry = new IndicatorRegistry(List.of(
                stub("MACD_V", DataRequirement.derivedFrom(10, "MACD", "ATR")),
                stub("MACD", DataRequirement.priceBars(35)),
                stub("ATR", DataRequirement.priceBars(14))));

        assertThat(registry.effectiveLookback("MACD")).isEqualTo(35);
        assertThat(registry.effectiveLookback("ATR")).isEqualTo(14);
        // 자기 몫 10 + 부모 중 최대(35) = 45
        assertThat(registry.effectiveLookback("MACD_V")).isEqualTo(45);
        assertThat(registry.maxEffectiveLookback()).isEqualTo(45);
    }

    private static IndicatorCalculator stub(String code, DataRequirement requirement) {
        return new IndicatorCalculator() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public DataRequirement dataRequirement() {
                return requirement;
            }

            @Override
            public List<IndicatorPoint> calculate(IndicatorContext context) {
                return List.of();
            }
        };
    }
}
