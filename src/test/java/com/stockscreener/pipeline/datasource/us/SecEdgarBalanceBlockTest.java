package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 켬 [Z] — 대차대조표는 블록이다. 새 판이 잔액 칸을 일부만 실어 앞 판 칸과 섞인 결과가 부채 + 자본 > 자산이면, 그 판은 잔액 블록 전체를 앞
 * 판 것으로 잇는다.
 *
 * <p>무너지는 방식이 조용하다 — 자본만 새 공시 값이고 자산 · 부채는 옛 공시 값인 행이 최신으로 남아 PBR · 부채비율이 두 공시를 섞는다.
 */
class SecEdgarBalanceBlockTest {

    private static String fact(String end, long val, String accn, String form, String filed) {
        return """
                {"end":"%s","val":%d,"accn":"%s","fy":2017,"fp":"FY","form":"%s","filed":"%s"}"""
                .formatted(end, val, accn, form, filed);
    }

    private static String flow(String start, String end, long val, String accn, String form, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":2017,"fp":"FY","form":"%s","filed":"%s"}"""
                .formatted(start, end, val, accn, form, filed);
    }

    private static RawFinancialReport latestFy(boolean usGate) throws Exception {
        String first = "0000000001-18-000001";
        String later = "0000000001-18-000009";
        // 10-K: 자산 1,000 · 부채 600 · 자본 400(섬). 뒤 10-K/A는 자본만 900으로 싣는다 — 이으면 부채 600 + 자본 900 > 자산 1,000.
        String json = """
                {"facts":{"us-gaap":{
                "Revenues":{"units":{"USD":[%s,%s]}},
                "Assets":{"units":{"USD":[%s]}},
                "Liabilities":{"units":{"USD":[%s]}},
                "StockholdersEquity":{"units":{"USD":[%s,%s]}}}}}""".formatted(
                flow("2017-01-01", "2017-12-31", 500, first, "10-K", "2018-02-15"),
                flow("2017-01-01", "2017-12-31", 510, later, "10-K/A", "2018-05-01"),
                fact("2017-12-31", 1_000_000_000L, first, "10-K", "2018-02-15"),
                fact("2017-12-31", 600_000_000L, first, "10-K", "2018-02-15"),
                fact("2017-12-31", 400_000_000L, first, "10-K", "2018-02-15"),
                fact("2017-12-31", 900_000_000L, later, "10-K/A", "2018-05-01"));
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        List<RawFinancialReport> reports = SecEdgarFacts.build(facts, 2015, usGate).reports();
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && "FY".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: 새 판이 잔액 블록을 깨면 앞 판 블록을 잇고 손익은 새 판 값을 받는다")
    void brokenBalanceBlockKeepsThePreviousBlock() throws Exception {
        RawFinancialReport on = latestFy(true);
        assertThat(on.totalEquity()).isEqualByComparingTo("400000000");
        assertThat(on.revenue()).isEqualByComparingTo("510");
        assertThat(latestFy(false).totalEquity()).as("관문 꺼짐은 옛 출력 그대로").isEqualByComparingTo("900000000");
    }
}
