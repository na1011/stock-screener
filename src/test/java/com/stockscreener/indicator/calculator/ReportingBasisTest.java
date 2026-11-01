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

    @Test
    @DisplayName("보이는 날 — 공개 시각 뒤 처음 마감하는 세션 · 시각을 모르면 공시일 다음 날 · 마감을 모르면 가장 이른 마감 · 공시일보다 이르지 않다")
    void visibleFromIsTheFirstCloseAfterOpening() {
        LocalDate d = LocalDate.of(2024, 7, 30);
        ReportingBasis.SessionClose regular = day -> java.time.LocalTime.of(16, 0);
        // MSFT 10-K 2024-07-30 16:06 ET → 그날 종가는 공개 전
        assertThat(ReportingBasis.visibleFrom(d, Instant.parse("2024-07-30T20:06:00Z"), true, regular)).isEqualTo(d.plusDays(1));
        assertThat(ReportingBasis.visibleFrom(d, Instant.parse("2024-07-30T13:00:00Z"), true, regular)).as("09:00 ET").isEqualTo(d);
        // AAPL 10-Q 2024-08-01 18:04 ET · 제출일 08-02 — 공시일보다 이르지 않다
        LocalDate filed = LocalDate.of(2024, 8, 2);
        assertThat(ReportingBasis.visibleFrom(filed, Instant.parse("2024-08-01T22:04:00Z"), true, regular)).isEqualTo(filed);
        assertThat(ReportingBasis.visibleFrom(d, null, true, regular)).as("시각 모름").isEqualTo(d.plusDays(1));
        // 조기 폐장 13:00 · 마감을 모르면 13:00으로
        LocalDate halfDay = LocalDate.of(2024, 11, 29);
        assertThat(ReportingBasis.visibleFrom(halfDay, Instant.parse("2024-11-29T19:30:00Z"), true, day -> java.time.LocalTime.of(13, 0)))
                .as("14:30 ET · 조기 폐장").isEqualTo(halfDay.plusDays(1));
        assertThat(ReportingBasis.visibleFrom(d, Instant.parse("2024-07-30T18:30:00Z"), true, day -> null))
                .as("14:30 ET · 마감 모름 → 13:00").isEqualTo(d.plusDays(1));
        // 국내 15:30 · 2016-08-01 앞은 15:00
        LocalDate k = LocalDate.of(2025, 11, 14);
        assertThat(ReportingBasis.visibleFrom(k, Instant.parse("2025-11-14T05:00:00Z"), false, null)).as("14:00 KST").isEqualTo(k);
        assertThat(ReportingBasis.visibleFrom(k, Instant.parse("2025-11-14T06:31:00Z"), false, null)).as("15:31 KST").isEqualTo(k.plusDays(1));
        LocalDate old = LocalDate.of(2016, 3, 15);
        assertThat(ReportingBasis.visibleFrom(old, Instant.parse("2016-03-15T06:10:00Z"), false, null)).as("15:10 KST · 15:00 마감 시절")
                .isEqualTo(old.plusDays(1));
    }
}
