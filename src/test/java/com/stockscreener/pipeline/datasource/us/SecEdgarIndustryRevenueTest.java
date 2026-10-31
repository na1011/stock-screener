package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.us.shareclass.CoverUnitJudge;

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
 * 관문 켬 업종 총매출 후보 — 유틸리티(SIC 49)는 총매출을 {@code RegulatedAndUnregulatedOperatingRevenue}로 싣고 계약 매출 태그는 그 일부다.
 * 픽스처는 NJR의 실제 companyfacts(FY2019 · 2018-10 ~ 2019-12)를 세 태그로 자른 것이다.
 *
 * <p>무너지는 방식이 조용하다 — 계약 매출(ASC 606)이 덮는 창은 부분값이 총매출 자리에 앉고 값이 그럴듯하다(782M 대 2,592M).
 */
class SecEdgarIndustryRevenueTest {

    private static List<RawFinancialReport> build(boolean usGate, String sic) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/sec/companyfacts-njr-utility-revenue.json");
        JsonNode facts = new ObjectMapper().readTree(Files.readString(path, StandardCharsets.UTF_8)).path("facts");
        return SecEdgarFacts.build(facts, 2018, usGate, CoverUnitJudge.Result.NONE,
                new IssuerProfile.Profile(9, sic)).reports();
    }

    private static RawFinancialReport latest(List<RawFinancialReport> reports, String period) {
        return reports.stream().filter(r -> r.fiscalYear() == 2019 && period.equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬 · 유틸리티(SIC 4924): 업종 총매출이 계약 매출과 최댓값을 다툰다 — FY 2,592M · 1분기 866M")
    void utilityTotalRevenueCompetes() throws Exception {
        List<RawFinancialReport> reports = build(true, "4924");

        assertThat(latest(reports, "FY").revenue()).as("계약 매출 782M은 부분집합").isEqualByComparingTo("2592045000");
        assertThat(latest(reports, "Q2").revenue()).as("1~3월 — 회계연도가 10월에 시작한다").isEqualByComparingTo("866255000");
    }

    @Test
    @DisplayName("그 업종이 아니거나 관문이 꺼지면 업종 태그를 안 본다 — 다른 회사에서는 한 부문이다(KKR · TRGP)")
    void otherIndustryOrGateOffKeepsTheContractTag() throws Exception {
        assertThat(latest(build(true, "6282"), "FY").revenue()).as("업종 밖 SIC").isEqualByComparingTo("782331000");
        assertThat(latest(build(true, null), "FY").revenue()).as("SIC 모름").isEqualByComparingTo("782331000");
        assertThat(latest(build(false, "4924"), "FY").revenue()).as("관문 꺼짐").isEqualByComparingTo("782331000");
    }
}
