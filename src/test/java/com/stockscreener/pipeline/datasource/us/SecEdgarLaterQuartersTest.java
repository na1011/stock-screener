package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 · 재고 A6 — 같은 해 앞 분기가 다른 기저로 다시 실린 날부터 뒤 분기는 새 기저가 확인될 때까지 모른다(두 시장 한 규칙 · 국내
 * {@code DartFlowBasis}). PNR 2017 2분기: 원 10-Q 매출 1,265 · 분사 뒤 2018 10-Q 비교열 754.
 */
class SecEdgarLaterQuartersTest {

    private static final long M = 1_000_000L;

    private static String fact(String start, String end, long val, String accn, String form, String fp, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"%s","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, fy, fp, form, filed);
    }

    private static List<RawFinancialReport> build(boolean usGate) throws Exception {
        String revenues = String.join(",",
                fact("2017-01-01", "2017-03-31", 1_200 * M, "0000000001-17-000001", "10-Q", "Q1", 2017, "2017-04-25"),
                fact("2017-04-01", "2017-06-30", 1_265 * M, "0000000001-17-000002", "10-Q", "Q2", 2017, "2017-07-25"),
                fact("2017-07-01", "2017-09-30", 1_250 * M, "0000000001-17-000003", "10-Q", "Q3", 2017, "2017-10-24"),
                // 회계연도 창을 세우는 연차 사실(10-K).
                fact("2017-01-01", "2017-12-31", 4_965 * M, "0000000001-18-000000", "10-K", "FY", 2017, "2018-02-15"),
                // 다음 해 1분기 10-Q의 비교열 — 분사로 전년 1분기가 다른 기저로 다시 실렸다.
                fact("2018-01-01", "2018-03-31", 720 * M, "0000000001-18-000001", "10-Q", "Q1", 2018, "2018-04-24"),
                fact("2017-01-01", "2017-03-31", 700 * M, "0000000001-18-000001", "10-Q", "Q1", 2018, "2018-04-24"));
        JsonNode facts = new ObjectMapper().readTree("{\"facts\":{\"us-gaap\":{\"Revenues\":{\"units\":{\"USD\":[" + revenues + "]}}}}}")
                .path("facts");
        return SecEdgarFacts.build(facts, 2015, usGate).reports();
    }

    @Test
    @DisplayName("앞 분기가 1% 넘게 다시 실린 날 — 같은 해 뒤 분기의 그 열은 그날부터 모른다(부재 판정) · 관문 꺼짐은 옛 기저 그대로")
    void laterQuarterIsUnknownFromTheRestatementDay() throws Exception {
        RawFinancialReport q2 = build(true).stream()
                .filter(r -> r.fiscalYear() == 2017 && "Q2".equals(r.fiscalPeriod()) && r.publishedAt().equals(LocalDate.of(2018, 4, 24)))
                .findFirst().orElseThrow();
        assertThat(q2.revenue()).isNull();
        assertThat(q2.assertedAbsent()).contains("revenue");
        assertThat(build(false)).as("관문 꺼짐")
                .noneMatch(r -> r.fiscalYear() == 2017 && "Q2".equals(r.fiscalPeriod()) && r.publishedAt().equals(LocalDate.of(2018, 4, 24)));
    }
}
