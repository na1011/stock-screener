package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 국내 관문 켬 A6 — 뒤 보고서가 실은 기간 값이 저장 값과 안 닫히면 그 공시일부터 그 계정의 그 해 분기는 낡았다(단계 셋 · 계정마다). */
class DartFlowBasisTest {

    private static final LocalDate Q1 = LocalDate.of(2024, 5, 14);
    private static final LocalDate H1 = LocalDate.of(2024, 8, 14);
    private static final LocalDate Q3 = LocalDate.of(2024, 11, 14);
    private static final LocalDate NEXT = LocalDate.of(2025, 5, 15);
    private static final DartFlowBasis.Dates DATES = new DartFlowBasis.Dates(Q1, H1, Q3, NEXT);

    private static BigDecimal b(long v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    @DisplayName("다 닫히면 대체 없음 — 1% 안의 차이도 같은 기저다")
    void closedYearHasNoSupersession() {
        Map<String, DartFlowBasis.Values> v = Map.of("revenue",
                new DartFlowBasis.Values(b(100), b(120), b(220), b(130), b(351), b(100)));
        assertThat(DartFlowBasis.judge(v, DATES)).isEmpty();
    }

    @Test
    @DisplayName("① 반기 누계 ≠ 1 + 2분기 → 1분기가 반기 공시일부터 낡았다(반기 보고서가 1분기를 고쳐 실었다 · 00107613 2021 꼴)")
    void halfYearRestatesTheFirstQuarter() {
        Map<String, DartFlowBasis.Values> v = Map.of("revenue",
                new DartFlowBasis.Values(b(122), b(118), b(217), b(129), b(346), null));
        Map<String, Map<String, LocalDate>> out = DartFlowBasis.judge(v, DATES);
        assertThat(out).containsOnlyKeys("Q1");
        assertThat(out.get("Q1")).containsEntry("revenue", H1);
    }

    @Test
    @DisplayName("② 9개월 누계 ≠ 반기 누계 + 3분기 → 1 · 2분기가 3분기 공시일부터 낡았다")
    void thirdQuarterRestatesTheHalfYear() {
        Map<String, DartFlowBasis.Values> v = Map.of("operating_income",
                new DartFlowBasis.Values(b(10), b(10), b(20), b(10), b(25), null));
        Map<String, Map<String, LocalDate>> out = DartFlowBasis.judge(v, DATES);
        assertThat(out.get("Q1")).containsEntry("operating_income", Q3);
        assertThat(out.get("Q2")).containsEntry("operating_income", Q3);
        assertThat(out).doesNotContainKey("Q3");
    }

    @Test
    @DisplayName("③ 다음 해 1분기 보고서의 전기 열 ≠ 1분기 → 그 해 1~4분기가 그 공시일부터 낡았다(011790 2023 꼴) · 다른 계정은 그대로")
    void nextYearComparativeRestatesTheWholeYear() {
        Map<String, DartFlowBasis.Values> v = Map.of(
                "revenue", new DartFlowBasis.Values(b(669), b(600), b(1269), b(600), b(1869), b(480)),
                "net_income", new DartFlowBasis.Values(b(50), b(50), b(100), b(50), b(150), b(50)));
        Map<String, Map<String, LocalDate>> out = DartFlowBasis.judge(v, DATES);
        assertThat(out).containsOnlyKeys("Q1", "Q2", "Q3", "Q4");
        assertThat(out.get("Q4")).containsOnly(Map.entry("revenue", NEXT));
    }

    @Test
    @DisplayName("대체일은 쓴 보고서 공시일의 최댓값 — 뒤 정정판으로 앞 날짜를 판정하지 않는다 · 같은 칸이 두 단계에 걸리면 이른 날")
    void sinceIsTheLatestFilingUsedAndTheEarliestStage() {
        DartFlowBasis.Dates late = new DartFlowBasis.Dates(Q1, LocalDate.of(2025, 3, 17), Q3, NEXT);
        Map<String, DartFlowBasis.Values> v = Map.of("revenue",
                new DartFlowBasis.Values(b(122), b(118), b(217), b(129), b(300), null));
        Map<String, Map<String, LocalDate>> out = DartFlowBasis.judge(v, late);
        assertThat(out.get("Q1")).containsEntry("revenue", LocalDate.of(2025, 3, 17));
    }
}
