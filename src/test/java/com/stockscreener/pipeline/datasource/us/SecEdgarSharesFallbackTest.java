package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축 없는 표지가 없는 공시의 주식수 폴백(탐색 {@code SHARES-TIE-PLAN-1023.md} v1.1). 픽스처는 실제 companyfacts를 매출 · 순이익 ·
 * 가중평균 두 태그로 자른 것이다 — TKO(Up-C · 희석이 교환 단위를 기간마다 넣고 뺀다)와 ACN(10-K가 12개월과 Q4 3개월을 함께 싣는다).
 *
 * <p>무너지는 방식이 조용하다 — 동점(같은 공시 · 같은 기말의 3개월과 누계)을 {@code HashMap} 순서로 고르면 경로마다 다른 값이 나오고
 * 값은 그럴듯하다(TKO 2026 Q2 누계 희석 193.3M 대 3개월 기본 74.8M).
 */
class SecEdgarSharesFallbackTest {

    private static List<RawFinancialReport> build(String fixture, int from, boolean usGate) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec", fixture);
        JsonNode facts = new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8)).path("facts");
        return SecEdgarFacts.build(facts, from, usGate).reports();
    }

    private static RawFinancialReport latest(List<RawFinancialReport> reports, int fiscalYear, String period) {
        return reports.stream()
                .filter(r -> r.fiscalYear() == fiscalYear && period.equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt))
                .orElseThrow();
    }

    @Test
    @DisplayName("관문 켬은 기본 가중평균의 가장 짧은 창을 고르고, 옛 규칙이 낼 수 있던 희석 값들을 대신한 값으로 단다(TKO 2026 Q2)")
    void gateOnPicksShortestBasicAndRecordsTheSupersededDiluted() throws Exception {
        RawFinancialReport q2 = latest(build("companyfacts-tko-weighted-shares.json", 2025, true), 2026, "Q2");

        assertThat(q2.sharesOutstanding()).as("3개월 기본 — 누계 기본 76.1M · 3개월 희석 75.9M · 누계 희석 193.3M")
                .isEqualByComparingTo("74797769");
        assertThat(q2.supersedesShares(new BigDecimal("193323374.00"))).as("옛 동점 후보(누계 희석) · 소수 자리 무관").isTrue();
        assertThat(q2.supersedesShares(new BigDecimal("75870706"))).as("옛 동점 후보(3개월 희석)").isTrue();
        assertThat(q2.supersedesShares(new BigDecimal("76054642"))).as("누계 기본은 옛 규칙이 낼 수 없었다").isFalse();
    }

    @Test
    @DisplayName("관문 꺼짐은 옛 규칙 그대로다 — 희석 동점 중 하나이고 대신한 값은 비어 있다")
    void gateOffKeepsTheLegacyPick() throws Exception {
        RawFinancialReport q2 = latest(build("companyfacts-tko-weighted-shares.json", 2025, false), 2026, "Q2");

        assertThat(q2.sharesOutstanding()).isIn(new BigDecimal("193323374"), new BigDecimal("75870706"));
        assertThat(q2.sharesSuperseded()).isEmpty();
    }

    @Test
    @DisplayName("같은 10-K의 FY와 Q4는 같은 주식수다 — 시점 값이라 12개월 평균이 아니라 Q4 3개월(ACN FY2025)")
    void fiscalYearAndFourthQuarterShareTheShortestWindow() throws Exception {
        List<RawFinancialReport> reports = build("companyfacts-acn-weighted-shares.json", 2025, true);

        RawFinancialReport fy = latest(reports, 2025, "FY");
        RawFinancialReport q4 = latest(reports, 2025, "Q4");
        assertThat(fy.sharesOutstanding()).as("Q4 3개월 기본 — 12개월 기본 624.9M").isEqualByComparingTo("622635814");
        assertThat(q4.sharesOutstanding()).isEqualByComparingTo(fy.sharesOutstanding());
        assertThat(fy.supersedesShares(new BigDecimal("632435108"))).isTrue();
        assertThat(fy.supersedesShares(new BigDecimal("629418129"))).isTrue();
    }

    @Test
    @DisplayName("동점이 없고 옛 규칙과 같은 값이면 대신한 값이 없다 — 표지가 있는 행은 이 축 밖이다")
    void noTieNoSuperseded() throws Exception {
        RawFinancialReport fy24 = latest(build("companyfacts-tko-weighted-shares.json", 2024, true), 2024, "FY");

        assertThat(fy24.sharesOutstanding()).as("12개월 기본 — 그 10-K에 Q4 3개월이 없다").isEqualByComparingTo("81340472");
        assertThat(fy24.supersedesShares(new BigDecimal("171874540"))).as("옛 사다리의 희석 12개월").isTrue();
        assertThat(fy24.supersedesShares(new BigDecimal("81340472"))).as("자기 값은 대신한 값이 아니다").isFalse();
    }

    @Test
    @DisplayName("표지 단위 판정이 버린 표지는 사다리 값이 대신하고, 그 표지와 손 수리 값을 대신한 값으로 단다(PKG 2025 FY · ADR-0193)")
    void aJudgedCoverIsReplacedAndItsHandRepairIsSuperseded() throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec/cover/companyfacts-pkg-cover.json");
        JsonNode facts = new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8)).path("facts");

        RawFinancialReport judged = latest(SecEdgarFacts.build(facts, 2025, true,
                ShareClassCollector.judgeCovers(facts, List.of())).reports(), 2025, "FY");
        assertThat(judged.sharesOutstanding()).as("10-K의 기본 가중평균(Q4 3개월)").isEqualByComparingTo("89300000");
        assertThat(judged.supersedesShares(new BigDecimal("89213394000"))).as("옛 추출 — 단위가 틀린 표지").isTrue();
        assertThat(judged.supersedesShares(new BigDecimal("89213394"))).as("그 손 수리(ADR-0126)").isTrue();

        // 대조: 판정 없이는 단위가 틀린 표지가 그대로 나간다.
        assertThat(latest(SecEdgarFacts.build(facts, 2025, true).reports(), 2025, "FY").sharesOutstanding())
                .isEqualByComparingTo("89213394000");
    }
}
