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
    @DisplayName("공시 보충이 더한 팩트(XBRL 인스턴스 · val이 문자열)도 본문 줄 경쟁을 탄다 — 2026-11-01 관문 재적재 44종목이 여기서 던졌다")
    void supplementFactWithTextValue() throws Exception {
        String json = String.join(",",
                tag("Revenues", 14_950 * MILLION),
                tag("RevenueFromContractWithCustomerExcludingAssessedTax", 21_700 * MILLION))
                .replaceAll("\"val\":(\\d+)", "\"val\":\"$1\"");
        assertThat(revenue(json, true, faceWith(14_950 * MILLION, 5_859 * MILLION))).isEqualByComparingTo("14950000000");
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

    @Test
    @DisplayName("판의 공개 시각 — 그날 사실을 낸 공시들의 접수 시각 최댓값 · 하나라도 모르면 모른다 · 그날 공시가 없어도 모른다(ADR-0204)")
    void openedAtIsTheLatestAcceptanceAndUnknownWins() throws Exception {
        java.time.Instant morning = java.time.Instant.parse("2024-07-30T13:00:00Z");
        java.time.Instant evening = java.time.Instant.parse("2024-07-30T20:06:00Z");
        Map<String, java.time.Instant> known = Map.of("A", morning, "B", evening);
        assertThat(SecEdgarFacts.openedAt(Set.of("A", "B"), known)).isEqualTo(evening);
        assertThat(SecEdgarFacts.openedAt(Set.of("A", "C"), known)).as("C를 모른다").isNull();
        assertThat(SecEdgarFacts.openedAt(Set.of(), known)).isNull();

        // build는 판 공시일에 사실을 낸 공시들로 짓는다 — 관문 꺼짐은 판정하지 않는다.
        String json = tag("Revenues", 14_950 * MILLION);
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{" + json + "}}}").path("facts");
        java.time.Instant accepted = java.time.Instant.parse("2018-02-15T21:10:00Z");
        RawFinancialReport on = SecEdgarFacts.build(facts, 2015, true, CoverUnitJudge.Result.NONE, IssuerProfile.Profile.UNKNOWN,
                SecStatementFaceCollector.SecStatementFaces.NONE, Map.of(ACCN, accepted)).reports().get(0);
        assertThat(on.acceptedAt()).isEqualTo(accepted);
        assertThat(on.openedJudged()).isTrue();
        RawFinancialReport off = SecEdgarFacts.build(facts, 2015, false, CoverUnitJudge.Result.NONE, IssuerProfile.Profile.UNKNOWN,
                SecStatementFaceCollector.SecStatementFaces.NONE, Map.of(ACCN, accepted)).reports().get(0);
        assertThat(off.openedJudged()).as("관문 꺼짐").isFalse();
    }

    @Test
    @DisplayName("앞 판이 본문 밖 영업이익을 거뒀으면 그 열을 안 실은 뒤 판도 「없다」를 잇는다 — 판정 없는 null은 저장이 옛 값을 안 지운다(CAG 2015)")
    void withdrawnColumnStaysAbsentInLaterVintages() throws Exception {
        // 첫 10-K: 영업이익 100은 본문 밖(부문 합계) · 본문은 매출 1,000과 순이익 50. 이듬해 공시가 2017 매출만 900으로 다시 싣는다.
        String json = String.join(",",
                restatedFact("Revenues", 1_000 * MILLION, 900 * MILLION),
                tag("OperatingIncomeLoss", 100 * MILLION),
                tag("NetIncomeLoss", 50 * MILLION));
        SecStatementFaceCollector.SecStatementFaces faces = new SecStatementFaceCollector.SecStatementFaces(Map.of(ACCN,
                new SecStatementFaceCollector.Face(Set.of("us-gaap_Revenues", "us-gaap_NetIncomeLoss"),
                        Set.of(SecStatementFaceCollector.Face.key("20171231", 4, BigDecimal.valueOf(1_000 * MILLION)),
                                SecStatementFaceCollector.Face.key("20171231", 4, BigDecimal.valueOf(50 * MILLION))))));
        RawFinancialReport latest = latestFy2017(json, true, faces);
        assertThat(latest.revenue()).isEqualByComparingTo("900000000");
        assertThat(latest.operatingIncome()).isNull();
        assertThat(latest.assertedAbsent()).as("뒤 판도 거둔 열을 「없다」로 싣는다").contains("operating_income");
    }

    private static final String RESTATED = "0000000001-19-000001";

    private static String restatedFact(String name, long first, long restated) {
        return ("\"%s\":{\"units\":{\"USD\":[%s,"
                + "{\"start\":\"2017-01-01\",\"end\":\"2017-12-31\",\"val\":%d,\"accn\":\"%s\",\"fy\":2018,\"fp\":\"FY\",\"form\":\"10-K\",\"filed\":\"2019-02-15\"}]}}")
                .formatted(name, fact(first), restated, RESTATED);
    }

    private static RawFinancialReport latestFy2017(String usGaap, boolean usGate, SecStatementFaceCollector.SecStatementFaces faces) throws Exception {
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{" + usGaap + "}}}").path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate, CoverUnitJudge.Result.NONE, IssuerProfile.Profile.UNKNOWN, faces).reports().stream()
                .filter(r -> r.fiscalYear() == 2017 && "FY".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    /** 재작성 공시의 본문 — 매출 줄과 재작성 영업이익(본문에 없으면 ADR-0202가 그 공시의 영업이익을 버려 재작성 판이 안 생긴다). */
    private static SecStatementFaceCollector.SecStatementFaces restatedFace(String concept, long value) {
        return new SecStatementFaceCollector.SecStatementFaces(Map.of(RESTATED, new SecStatementFaceCollector.Face(
                Set.of("us-gaap_" + concept, "us-gaap_OperatingIncomeLoss"),
                Set.of(SecStatementFaceCollector.Face.key("20171231", 4, BigDecimal.valueOf(value)),
                        SecStatementFaceCollector.Face.key("20171231", 4, BigDecimal.valueOf(5_966 * MILLION))))));
    }

    @Test
    @DisplayName("고정 태그가 없는 재작성 판 — 그 공시 본문의 매출 줄을 받는다 · 같은 줄의 원가가 앞 판에서 이어지면 받지 않는다 · 관문 꺼짐은 이어받기(DAL FY2017)")
    void restatedVintageTakesItsOwnFaceLine() throws Exception {
        // DAL FY2017: 원 10-K `Revenues` 41,244 · 재작성 10-K(ASC 606)는 `RevenueFromContractWithCustomerExcludingAssessedTax` 41,138만 싣는다.
        String json = String.join(",",
                tag("Revenues", 41_244 * MILLION),
                "\"RevenueFromContractWithCustomerExcludingAssessedTax\":{\"units\":{\"USD\":[{\"start\":\"2017-01-01\",\"end\":\"2017-12-31\","
                        + "\"val\":41138000000,\"accn\":\"" + RESTATED + "\",\"fy\":2018,\"fp\":\"FY\",\"form\":\"10-K\",\"filed\":\"2019-02-15\"}]}}",
                restatedFact("OperatingIncomeLoss", 6_114 * MILLION, 5_966 * MILLION));
        SecStatementFaceCollector.SecStatementFaces face = restatedFace("RevenueFromContractWithCustomerExcludingAssessedTax", 41_138 * MILLION);
        assertThat(latestFy2017(json, true, face).revenue()).isEqualByComparingTo("41138000000");
        assertThat(latestFy2017(json, false, face).revenue()).as("관문 꺼짐은 옛 출력").isEqualByComparingTo("41244000000");
        assertThat(latestFy2017(json, true, SecStatementFaceCollector.SecStatementFaces.NONE).revenue()).as("재료를 모르면 이어받기")
                .isEqualByComparingTo("41244000000");

        String withCarriedCost = json + "," + tag("CostOfRevenue", 30_000 * MILLION);
        assertThat(latestFy2017(withCarriedCost, true, face).revenue()).as("원가가 앞 판에서 이어진다 — 한 행에 기저가 섞인다")
                .isEqualByComparingTo("41244000000");
    }

    @Test
    @DisplayName("원가도 같은 원칙 — 본문이 상품 · 용역 원가만 싣고 합계가 어느 본문에도 없으면(부분 개념 줄 + 후보 줄 = 고른 값) 그대로(WAT FY2016)")
    void costSplitFaceKeepsTheTotal() throws Exception {
        String json = String.join(",",
                tag("CostOfGoodsAndServicesSold", 891_500_000L),
                tag("CostOfGoodsSold", 595_800_000L),
                tag("CostOfServices", 295_700_000L),
                tag("Revenues", 2_167_400_000L));
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{" + json + "}}}").path("facts");
        RawFinancialReport fy = SecEdgarFacts.build(facts, 2015, true, CoverUnitJudge.Result.NONE, IssuerProfile.Profile.UNKNOWN,
                faceWith(595_800_000L, 295_700_000L, 2_167_400_000L)).reports().stream()
                .filter(r -> r.fiscalYear() == 2017 && "FY".equals(r.fiscalPeriod())).findFirst().orElseThrow();
        assertThat(fy.costOfSales()).isEqualByComparingTo("891500000");
    }
}
