package com.stockscreener.pipeline.collector.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 등록인 포함 검사의 네 갈래(ADR-0180). */
class RegistrantContainmentTest {

    @Test
    @DisplayName("전부 듦 · 하나도 안 듦 · 일부 · 저장 없음")
    void fourOutcomes() {
        Set<String> known = Set.of("a", "b", "c");

        assertThat(RegistrantContainment.check(Set.of("a", "b"), known).result()).isEqualTo(RegistrantContainment.Result.ALL);
        assertThat(RegistrantContainment.check(Set.of("x", "y"), known).result()).isEqualTo(RegistrantContainment.Result.NONE);
        RegistrantContainment.Check partial = RegistrantContainment.check(Set.of("a", "x"), known);
        assertThat(partial.result()).isEqualTo(RegistrantContainment.Result.PARTIAL);
        assertThat(partial.missing()).isEqualTo(1);
        assertThat(partial.describe()).contains("누락 1").contains("x");
        assertThat(RegistrantContainment.check(Set.of(), known).result()).isEqualTo(RegistrantContainment.Result.NO_STORED);
    }
}
