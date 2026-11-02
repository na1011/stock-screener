package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 P3b — 10-Q가 같은 태그로 반기 누계와 2분기를 실으면 그 차가 그 공시가 말하는 1분기다. 그날까지의 1분기와 1% 넘게 다를 때만 받는다.
 *
 * <p>무너지는 방식이 조용하다 — 다음 해 2분기 10-Q가 전년 반기를 새 기준(매각)으로 다시 싣고 1분기 3개월은 안 다시 실으면 1분기가 옛 기준에 남고,
 * 그 해 4분기의 세대가 영영 안 선다(BC 2019 · EW 2024 · KMB 2025).
 */
class SecEdgarImpliedFirstQuarterTest {

    private static final long MILLION = 1_000_000L;

    private static String fact(String start, String end, long val, String accn, String form, String fp, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val * MILLION, accn, fy, fp, form, filed);
    }

    /** 2017년 분기 셋(각 200) · 2018년 1분기 · 2018년 2분기 10-Q(자기 분기 · 반기 + 전년 비교 2분기 150 · 전년 반기 {@code priorHalf}). */
    private static List<RawFinancialReport> build(long priorHalf) throws Exception {
        List<String> revenue = new ArrayList<>(List.of(
                fact("2017-01-01", "2017-03-31", 200, "0000000001-17-000001", "10-Q", "Q1", 2017, "2017-05-01"),
                fact("2017-04-01", "2017-06-30", 200, "0000000001-17-000002", "10-Q", "Q2", 2017, "2017-08-01"),
                fact("2017-07-01", "2017-09-30", 200, "0000000001-17-000003", "10-Q", "Q3", 2017, "2017-11-01"),
                fact("2017-01-01", "2017-12-31", 800, "0000000001-18-000001", "10-K", "FY", 2017, "2018-02-15"),
                fact("2018-01-01", "2018-03-31", 300, "0000000001-18-000002", "10-Q", "Q1", 2018, "2018-05-01"),
                fact("2018-04-01", "2018-06-30", 300, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"),
                fact("2018-01-01", "2018-06-30", 600, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"),
                fact("2017-04-01", "2017-06-30", 150, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01"),
                fact("2017-01-01", "2017-06-30", priorHalf, "0000000001-18-000003", "10-Q", "Q2", 2018, "2018-08-01")));
        String json = """
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s]}}}}}""".formatted(String.join(",", revenue));
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        return SecEdgarFacts.build(facts, 2015).reports();
    }

    private static RawFinancialReport latestQ1(List<RawFinancialReport> reports) {
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && "Q1".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("전년 반기(300) − 전년 2분기(150)가 옛 1분기(200)와 다르면 그 10-Q의 1분기 판(150)을 낸다")
    void halfYearMinusSecondQuarterRestatesTheFirstQuarter() throws Exception {
        assertThat(latestQ1(build(300)).revenue()).isEqualByComparingTo("150000000");
    }

    @Test
    @DisplayName("함의 1분기가 옛 1분기와 1% 안이면 같은 세대라 판을 안 낸다")
    void impliedFirstQuarterWithinOnePercentAddsNoVintage() throws Exception {
        // 전년 반기 351 − 2분기 150 = 201 — 옛 1분기 200과 0.5%.
        RawFinancialReport q1 = latestQ1(build(351));
        assertThat(q1.revenue()).isEqualByComparingTo("200000000");
        assertThat(q1.publishedAt()).hasToString("2017-05-01");
    }
}
