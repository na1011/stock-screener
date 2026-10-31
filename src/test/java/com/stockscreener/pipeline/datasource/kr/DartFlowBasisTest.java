package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 국내 관문 켬 A6(ADR-0200) — 보고서가 실은 기간 값이 저장 값과 안 닫히면 그 공시일부터 그 칸은 낡았다(계정마다 · 그 해 누계 · 다음 해 전기 열). */
class DartFlowBasisTest {

    private static final LocalDate Q1 = LocalDate.of(2024, 5, 14);
    private static final LocalDate H1 = LocalDate.of(2024, 8, 14);
    private static final LocalDate Q3 = LocalDate.of(2024, 11, 14);
    private static final LocalDate FY = LocalDate.of(2025, 3, 18);
    private static final LocalDate NQ1 = LocalDate.of(2025, 5, 15);
    private static final LocalDate NH1 = LocalDate.of(2025, 8, 14);
    private static final LocalDate NQ3 = LocalDate.of(2025, 11, 14);
    private static final LocalDate NFY = LocalDate.of(2026, 3, 17);
    private static final DartFlowBasis.Dates DATES = new DartFlowBasis.Dates(Q1, H1, Q3, FY, NQ1, NH1, NQ3, NFY);

    private static BigDecimal b(Long v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    /** 그 해: 1 · 2 · 3분기 3개월 100 · 120 · 130 · 반기 220 · 9개월 350 · FY 500. 다음 해 전기 열은 인자로. */
    private static DartFlowBasis.Values year(Long pq1, Long pq2, Long ph1, Long pq3, Long p9, Long pfy) {
        return new DartFlowBasis.Values(b(100L), b(120L), b(220L), b(130L), b(350L), b(500L), b(pq1), b(pq2), b(ph1), b(pq3), b(p9), b(pfy));
    }

    @Test
    @DisplayName("다 닫히면 대체 없음 — 1% 안의 차이도 같은 기저다")
    void closedYearHasNoSupersession() {
        assertThat(DartFlowBasis.judge(Map.of("revenue", year(100L, 120L, 220L, 130L, 351L, 500L)), DATES)).isEmpty();
    }

    @Test
    @DisplayName("① 반기 누계 ≠ 1 + 2분기 → 1분기가 반기 공시일부터 낡았다(00107613 2021 꼴)")
    void halfYearRestatesTheFirstQuarter() {
        DartFlowBasis.Values v = new DartFlowBasis.Values(b(122L), b(118L), b(217L), b(129L), b(346L), null,
                null, null, null, null, null, null);
        Map<String, Map<String, LocalDate>> out = DartFlowBasis.judge(Map.of("revenue", v), DATES);
        assertThat(out).containsOnlyKeys("Q1");
        assertThat(out.get("Q1")).containsEntry("revenue", H1);
    }

    @Test
    @DisplayName("③ 다음 해 1분기 전기 열 ≠ 1분기 → 그 해 1~4분기가 그 공시일부터 · 다른 계정은 그대로(011790 2023 꼴)")
    void nextFirstQuarterRestatesTheYear() {
        Map<String, Map<String, LocalDate>> out = DartFlowBasis.judge(Map.of(
                "revenue", year(80L, null, null, null, null, null),
                "net_income", year(100L, null, null, null, null, null)), DATES);
        assertThat(out).containsOnlyKeys("Q1", "Q2", "Q3", "Q4");
        assertThat(out.get("Q4")).containsOnly(Map.entry("revenue", NQ1));
    }

    @Test
    @DisplayName("④ · ⑤ 다음 해 반기 · 3분기의 전기 3개월 열은 그 분기와 뒤 분기를 낡게 한다")
    void laterComparativesSupersedeThatQuarterAndAfter() {
        Map<String, Map<String, LocalDate>> second = DartFlowBasis.judge(Map.of("revenue", year(100L, 110L, null, null, null, null)), DATES);
        assertThat(second).containsOnlyKeys("Q2", "Q3", "Q4");
        assertThat(second.get("Q2")).containsEntry("revenue", NH1);

        Map<String, Map<String, LocalDate>> third = DartFlowBasis.judge(Map.of("revenue", year(100L, 120L, null, 125L, null, null)), DATES);
        assertThat(third).containsOnlyKeys("Q3", "Q4");
        assertThat(third.get("Q3")).containsEntry("revenue", NQ3);
    }

    @Test
    @DisplayName("⑥ 다음 해 사업보고서의 전기 FY ≠ FY → FY · 전기 FY − 전기 9개월 ≠ 4분기 → 4분기")
    void nextAnnualComparativeSupersedesFiscalYearAndFourthQuarter() {
        Map<String, Map<String, LocalDate>> out = DartFlowBasis.judge(Map.of("revenue", year(null, null, null, null, 350L, 480L)), DATES);
        assertThat(out.get("FY")).containsEntry("revenue", NFY);
        assertThat(out.get("Q4")).containsEntry("revenue", NFY);
        assertThat(out).doesNotContainKeys("Q1", "Q2", "Q3");
    }

    @Test
    @DisplayName("저장 값이 0이면 0이 아닌 전기 열이 곧 어긋남이다(중단영업은 대개 0에서 생긴다)")
    void zeroStoredValueIsApartFromAnyNonZero() {
        assertThat(DartFlowBasis.apart(BigDecimal.valueOf(5), BigDecimal.ZERO, null)).isTrue();
        assertThat(DartFlowBasis.apart(BigDecimal.ZERO, BigDecimal.ZERO, null)).isFalse();
    }

    @Test
    @DisplayName("대체일은 쓴 보고서 공시일의 최댓값 · 한 칸이 여러 짝에 걸리면 이른 날")
    void sinceIsTheLatestFilingUsedAndTheEarliestPair() {
        DartFlowBasis.Dates late = new DartFlowBasis.Dates(Q1, LocalDate.of(2025, 3, 17), Q3, FY, NQ1, NH1, NQ3, NFY);
        DartFlowBasis.Values v = new DartFlowBasis.Values(b(122L), b(118L), b(217L), b(129L), b(300L), null,
                null, null, null, null, null, null);
        assertThat(DartFlowBasis.judge(Map.of("revenue", v), late).get("Q1")).containsEntry("revenue", LocalDate.of(2025, 3, 17));
    }
}
