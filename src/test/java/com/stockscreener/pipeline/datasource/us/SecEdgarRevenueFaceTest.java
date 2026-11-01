package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.us.shareclass.CoverUnitJudge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 — 매출 · 원가의 최댓값 경쟁은 그 공시 손익계산서 본문의 줄인 후보가 있으면 그것들만 다툰다(재고 「본문 밖 매출 · 원가 후보가 최댓값으로
 * 이김」). MA FY2018: 본문 순매출 14,950 대 주석의 리베이트 전 총액 `RevenueFromContractWithCustomerExcludingAssessedTax` 21,7xx.
 */
class SecEdgarRevenueFaceTest {

    private static final long MILLION = 1_000_000L;
    private static final String ACCN = "0000000001-18-000001";

    private static String fact(long val) {
        return """
                {"start":"2017-01-01","end":"2017-12-31","val":%d,"accn":"%s","fy":2017,"fp":"FY","form":"10-K","filed":"2018-02-15"}"""
                .formatted(val, ACCN);
    }

    private static String tag(String name, long val) {
        return "\"%s\":{\"units\":{\"USD\":[%s]}}".formatted(name, fact(val));
    }

    private static BigDecimal revenue(String usGaap, boolean usGate, SecStatementFaceCollector.SecStatementFaces faces) throws Exception {
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{" + usGaap + "}}}").path("facts");
        List<RawFinancialReport> reports = SecEdgarFacts.build(facts, 2015, usGate, CoverUnitJudge.Result.NONE,
                IssuerProfile.Profile.UNKNOWN, faces).reports();
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && "FY".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow().revenue();
    }

    private static SecStatementFaceCollector.SecStatementFaces faceWith(long... incomeValues) {
        Set<String> values = new java.util.HashSet<>();
        for (long v : incomeValues) {
            values.add(SecStatementFaceCollector.Face.key("20171231", 4, BigDecimal.valueOf(v)));
        }
        return new SecStatementFaceCollector.SecStatementFaces(Map.of(ACCN,
                new SecStatementFaceCollector.Face(Set.of("us-gaap_Revenues", "us-gaap_NetIncomeLoss"), values)));
    }

    @Test
    @DisplayName("본문 줄 후보가 있으면 그것들만 다툰다 — 주석의 더 큰 총액이 본문 매출을 밀어내지 않는다 · 관문 꺼짐은 최댓값 그대로")
    void faceCandidateBeatsLargerNoteCandidate() throws Exception {
        String json = String.join(",",
                tag("Revenues", 14_950 * MILLION),
                tag("RevenueFromContractWithCustomerExcludingAssessedTax", 21_700 * MILLION));
        SecStatementFaceCollector.SecStatementFaces faces = faceWith(14_950 * MILLION, 5_859 * MILLION);
        assertThat(revenue(json, true, faces)).isEqualByComparingTo("14950000000");
        assertThat(revenue(json, false, faces)).as("관문 꺼짐은 옛 출력").isEqualByComparingTo("21700000000");
    }

    @Test
    @DisplayName("본문 줄 후보가 없거나 공시를 모르면 전부가 최댓값을 다툰다")
    void noFaceCandidateKeepsLargest() throws Exception {
        String json = String.join(",",
                tag("Revenues", 14_950 * MILLION),
                tag("RevenueFromContractWithCustomerExcludingAssessedTax", 21_700 * MILLION));
        assertThat(revenue(json, true, faceWith(5_859 * MILLION))).isEqualByComparingTo("21700000000");
        assertThat(revenue(json, true, SecStatementFaceCollector.SecStatementFaces.NONE)).isEqualByComparingTo("21700000000");
    }

    @Test
    @DisplayName("본문이 합계 없이 매출을 나눠 실었으면(본문 매출 후보 줄 + 본문의 다른 매출 개념 줄 = 고른 값) 그대로 — 차액이 매출 개념이 아니면 바꾼다")
    void splitFaceKeepsTheTotal() throws Exception {
        String split = String.join(",",
                tag("Revenues", 3_711_800_000L),
                tag("SalesRevenueNet", 3_430_400_000L),
                tag("FinancialServicesRevenue", 281_400_000L));
        assertThat(revenue(split, true, faceWith(3_430_400_000L, 281_400_000L))).as("SNA 2016").isEqualByComparingTo("3711800000");

        String coincidence = String.join(",",
                tag("Revenues", 18_300_000_000L),
                tag("SalesRevenueNet", 18_259_000_000L),
                tag("NetIncomeLossAttributableToNoncontrollingInterest", 41_000_000L));
        assertThat(revenue(coincidence, true, faceWith(18_259_000_000L, 41_000_000L))).as("KMB 2017 — 차액이 비지배 순이익")
                .isEqualByComparingTo("18259000000");
    }
}
