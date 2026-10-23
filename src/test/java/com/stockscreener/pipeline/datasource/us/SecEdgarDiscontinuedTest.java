package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미국 관문 켬의 중단영업 판정(F2b · ADR-0184 · 탐색 {@code F2B-PLAN-1023.md}). 픽스처는 실제 companyfacts를 순이익 · 중단 ·
 * 계속영업 · 매출 · 잔액 태그로 자른 것이다(2024 뒤 기말).
 *
 * <p>무너지는 방식이 조용하다 — Q4를 {@code FY − (Q1 + Q2 + Q3)}로 지으면 해 중간 재분류 몫이 Q4로 몰려도 값은 그럴듯하다
 * (MLM FY2025: 94 대 참값 46).
 */
class SecEdgarDiscontinuedTest {

    private static final int FROM = 2024;

    private static List<RawFinancialReport> build(String fixture, boolean usGate) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec", fixture);
        JsonNode facts = new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8)).path("facts");
        return SecEdgarFacts.build(facts, FROM, usGate).reports();
    }

    private static RawFinancialReport latest(List<RawFinancialReport> reports, int fiscalYear, String period) {
        return reports.stream()
                .filter(r -> r.fiscalYear() == fiscalYear && period.equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt))
                .orElseThrow();
    }

    @Test
    @DisplayName("관문이 꺼지면 중단영업을 판정하지 않는다 — 세 칸이 비고 행은 이 축이 없던 때와 같다")
    void gateOffLeavesTheAxisUnread() throws Exception {
        assertThat(build("companyfacts-mlm-discontinued.json", false))
                .allSatisfy(r -> {
                    assertThat(r.discontinuedRule()).isNull();
                    assertThat(r.discontinuedIncome()).isNull();
                    assertThat(r.discontinuedIncomeControlling()).isNull();
                });
    }

    @Test
    @DisplayName("Q4 중단영업은 FY − 같은 해 9개월 누계다 — 분기 원 공시에 중단 줄이 없어도 재분류 몫이 Q4로 몰리지 않는다(MLM 2025)")
    void fourthQuarterIsFiscalYearMinusNineMonths() throws Exception {
        List<RawFinancialReport> reports = build("companyfacts-mlm-discontinued.json", true);

        RawFinancialReport q4 = latest(reports, 2025, "Q4");
        assertThat(q4.discontinuedRule()).isEqualByComparingTo("1");
        assertThat(q4.discontinuedIncome()).as("FY 147M − 9개월 101M").isEqualByComparingTo("46000000");
        assertThat(q4.discontinuedIncomeControlling()).isEqualByComparingTo("46000000");
        assertThat(latest(reports, 2026, "Q1").discontinuedIncomeControlling())
                .as("매각 차익 분기 — PER 분자에서 빠진다").isEqualByComparingTo("1434000000");
    }

    @Test
    @DisplayName("10-K 분기 표가 연결 몫만 쪼개면 지배 몫은 같은 기간의 잔차(FY − 9개월) 것이다(PAA 2025 Q4)")
    void controllingShareComesFromTheResidualWhenTheQuarterlyTableOmitsIt() throws Exception {
        List<RawFinancialReport> reports = build("companyfacts-paa-discontinued.json", true);

        RawFinancialReport q4 = latest(reports, 2025, "Q4");
        assertThat(q4.discontinuedIncome()).isEqualByComparingTo("102000000");
        assertThat(q4.discontinuedIncomeControlling())
                .as("FY 지배 383M(지배 순이익 1,435 − 계속영업 1,052) − 9개월 281M").isEqualByComparingTo("102000000");
    }

    @Test
    @DisplayName("중단 재료가 하나도 없는 라벨은 0이다 — 「읽었고 줄이 없다」")
    void noDiscontinuedLineIsZero() throws Exception {
        RawFinancialReport q1 = build("companyfacts-mlm-discontinued.json", true).stream()
                .filter(r -> r.fiscalYear() == 2024 && "Q1".equals(r.fiscalPeriod()))
                .findFirst().orElseThrow();

        assertThat(q1.discontinuedRule()).isEqualByComparingTo("1");
        assertThat(q1.discontinuedIncome()).isEqualByComparingTo("0");
        assertThat(q1.discontinuedIncomeControlling()).isEqualByComparingTo("0");
    }
}
