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
 * 관문 켬 K4 — 기간마다 고정한 태그가 뒤 공시에 없고 그 공시가 다른 후보 태그로 그 기간을 다시 실었으면, 그 공시 안의 「매출 − 원가 = 총이익」이 1% 안에서
 * 닫힐 때만 그 값을 받는다. 못 닫으면 앞 판 값을 잇고, 다른 태그 값이 앞 값을 반올림한 것이면 앞의 정밀한 값을 잇는다.
 *
 * <p>무너지는 방식이 조용하다 — 뒤 공시의 재작성이 안 들어오고 이어받기가 옛 값을 앉힌다(CHRD FY2017 순액 1,248.4 대 ASC 606 총액 재작성 1,293.7).
 */
class SecEdgarOwnGrossMarginTest {

    private static final String FIRST = "0000000001-18-000001";
    private static final String LATER = "0000000001-19-000001";

    private static String fact(String start, String end, long val, String accn, int fy, String filed) {
        return """
                {"start":"%s","end":"%s","val":%d,"accn":"%s","fy":%d,"fp":"FY","form":"10-K","filed":"%s"}"""
                .formatted(start, end, val, accn, fy, filed);
    }

    /** 첫 10-K는 {@code Revenues}로 FY2017을, 뒤 10-K는 {@code RevenueFromContract…}로 FY2018과 FY2017 비교열을 싣는다. */
    private static RawFinancialReport latestFy2017(long revenue, long cost, long gross, long laterRevenue, long laterCost,
                                                   long laterGross, boolean usGate) throws Exception {
        String json = """
                {"facts":{"us-gaap":{
                "Revenues":{"units":{"USD":[%s]}},
                "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{"USD":[%s,%s]}},
                "CostOfRevenue":{"units":{"USD":[%s,%s,%s]}},
                "GrossProfit":{"units":{"USD":[%s,%s,%s]}}}}}"""
                .formatted(
                        fact("2017-01-01", "2017-12-31", revenue, FIRST, 2017, "2018-02-15"),
                        fact("2018-01-01", "2018-12-31", 2 * laterRevenue, LATER, 2018, "2019-02-15"),
                        fact("2017-01-01", "2017-12-31", laterRevenue, LATER, 2018, "2019-02-15"),
                        fact("2017-01-01", "2017-12-31", cost, FIRST, 2017, "2018-02-15"),
                        fact("2018-01-01", "2018-12-31", 2 * laterCost, LATER, 2018, "2019-02-15"),
                        fact("2017-01-01", "2017-12-31", laterCost, LATER, 2018, "2019-02-15"),
                        fact("2017-01-01", "2017-12-31", gross, FIRST, 2017, "2018-02-15"),
                        fact("2018-01-01", "2018-12-31", 2 * laterGross, LATER, 2018, "2019-02-15"),
                        fact("2017-01-01", "2017-12-31", laterGross, LATER, 2018, "2019-02-15"));
        JsonNode facts = new ObjectMapper().readTree(json).path("facts");
        List<RawFinancialReport> reports = SecEdgarFacts.build(facts, 2015, usGate).reports();
        return reports.stream().filter(r -> r.fiscalYear() == 2017 && "FY".equals(r.fiscalPeriod()))
                .max(Comparator.comparing(RawFinancialReport::publishedAt)).orElseThrow();
    }

    @Test
    @DisplayName("관문 켬: 뒤 공시가 다른 태그로 실은 매출이 그 공시의 원가 · 총이익과 항등식을 닫으면 그 판의 값이다")
    void otherTagClosingTheIdentityIsTaken() throws Exception {
        assertThat(latestFy2017(1_000, 600, 400, 1_100, 650, 450, true).revenue()).isEqualByComparingTo("1100");
        assertThat(latestFy2017(1_000, 600, 400, 1_100, 650, 450, false).revenue())
                .as("관문 꺼짐은 옛 출력 그대로 — 고정 태그의 옛 값을 잇는다").isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("항등식이 안 닫히면(다른 개념일 수 있다) 앞 판 값을 잇는다")
    void otherTagNotClosingTheIdentityIsIgnored() throws Exception {
        assertThat(latestFy2017(1_000, 600, 400, 1_100, 650, 300, true).revenue()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("다른 태그 값이 앞 값을 자기 자릿수로 반올림한 것이면 앞의 정밀한 값을 잇는다 — 판이 오가지 않는다")
    void roundedOtherTagKeepsThePreciseValue() throws Exception {
        assertThat(latestFy2017(1_234_567, 600_000, 634_567, 1_235_000, 600_000, 635_000, true).revenue())
                .isEqualByComparingTo("1234567");
    }
}
