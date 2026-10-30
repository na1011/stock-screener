package com.stockscreener.indicator.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 실적 발표 공개일 · 다음 기말(ADR-0195). */
class ReportingBasisTest {

    @Test
    @DisplayName("미국 8-K는 동부 16:00 전 접수면 그 날, 뒤면 다음 날 봉부터 공개다 — RMD 10-29 16:05 ET는 10-30")
    void effectiveOnFollowsTheUsClose() {
        // RMD 8-K acceptanceDateTime 2026-10-29T20:05:45Z = 16:05 EDT
        assertThat(ReportingBasis.effectiveOn(LocalDate.of(2026, 10, 29), Instant.parse("2026-10-29T20:05:45Z")))
                .isEqualTo(LocalDate.of(2026, 10, 30));
        // 장 전 07:30 EDT
        assertThat(ReportingBasis.effectiveOn(LocalDate.of(2026, 10, 29), Instant.parse("2026-10-29T11:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 29));
        // 겨울(EST) 15:59 = 20:59Z — 마감 전
        assertThat(ReportingBasis.effectiveOn(LocalDate.of(2026, 1, 29), Instant.parse("2026-01-29T20:59:00Z")))
                .isEqualTo(LocalDate.of(2026, 1, 29));
    }

    @Test
    @DisplayName("접수 시각을 모르면(국내 · 미국 미해결) 접수일이다 — 보수적으로 그 봉 하나를 일찍 막는다")
    void effectiveOnWithoutTimeIsTheFilingDate() {
        assertThat(ReportingBasis.effectiveOn(LocalDate.of(2026, 10, 8), null)).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("다음 기말 — 월말은 월말로 맞추고 52/53주 결산은 날짜 그대로 석 달 뒤다")
    void nextPeriodEnd() {
        assertThat(ReportingBasis.nextPeriodEnd(LocalDate.of(2026, 6, 30))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(ReportingBasis.nextPeriodEnd(LocalDate.of(2026, 3, 31))).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(ReportingBasis.nextPeriodEnd(LocalDate.of(2025, 11, 30))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(ReportingBasis.nextPeriodEnd(LocalDate.of(2026, 6, 27))).isEqualTo(LocalDate.of(2026, 9, 27));
    }
}
