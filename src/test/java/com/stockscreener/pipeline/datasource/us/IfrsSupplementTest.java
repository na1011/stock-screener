package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공시 보충의 IFRS 몫(ADR-0182). companyfacts가 IFRS 공시를 빠뜨린다(182 IFRS 발행사 · 2023 뒤 6-K 97/375 · 20-F 27/567) —
 * 그 공시의 인스턴스에서 {@code ifrs-full}을 읽고, 관문 켬은 companyfacts가 IFRS로 실은 공시를 「덮였다」로 센다.
 */
class IfrsSupplementTest {

    private static final String INSTANCE = """
            <?xml version="1.0" encoding="utf-8"?>
            <xbrl xmlns="http://www.xbrl.org/2003/instance"
                  xmlns:dei="http://xbrl.sec.gov/dei/2025"
                  xmlns:iso4217="http://www.xbrl.org/2003/iso4217"
                  xmlns:ifrs-full="http://xbrl.ifrs.org/taxonomy/2025-03-27/ifrs-full"
                  xmlns:xbrli="http://www.xbrl.org/2003/instance">
              <context id="d"><entity><identifier scheme="http://www.sec.gov/CIK">0000811809</identifier></entity>
                <period><startDate>2025-07-01</startDate><endDate>2026-06-30</endDate></period></context>
              <context id="i"><entity><identifier scheme="http://www.sec.gov/CIK">0000811809</identifier></entity>
                <period><instant>2026-06-30</instant></period></context>
              <unit id="usd"><measure>iso4217:USD</measure></unit>
              <dei:DocumentType contextRef="d">20-F</dei:DocumentType>
              <dei:DocumentFiscalYearFocus contextRef="d">2026</dei:DocumentFiscalYearFocus>
              <dei:DocumentFiscalPeriodFocus contextRef="d">FY</dei:DocumentFiscalPeriodFocus>
              <ifrs-full:Revenue contextRef="d" unitRef="usd" decimals="-6">51262000000</ifrs-full:Revenue>
              <ifrs-full:Assets contextRef="i" unitRef="usd" decimals="-6">108000000000</ifrs-full:Assets>
            </xbrl>
            """;

    @Test
    @DisplayName("인스턴스의 ifrs-full 팩트를 그 택소노미로 읽는다(BHP 2026-08-18 20-F 꼴 — companyfacts에 없다)")
    void readsIfrsFactsFromTheInstance() throws Exception {
        XbrlInstanceReader.Instance instance = XbrlInstanceReader.read(
                new ByteArrayInputStream(INSTANCE.getBytes(StandardCharsets.UTF_8)), 811809, "test");
        List<XbrlInstanceReader.Fact> chosen = XbrlInstanceReader.choose(instance.facts(), "test");

        assertThat(chosen).extracting(XbrlInstanceReader.Fact::taxonomy, XbrlInstanceReader.Fact::concept)
                .contains(org.assertj.core.groups.Tuple.tuple("ifrs-full", "Revenue"),
                        org.assertj.core.groups.Tuple.tuple("ifrs-full", "Assets"));
    }

    @Test
    @DisplayName("관문 켬만 companyfacts가 IFRS로 실은 공시를 「덮였다」로 센다 — 꺼짐은 지금처럼 보충 대상")
    void countsIfrsFilingsAsCoveredOnlyBehindTheGate() throws Exception {
        ObjectNode facts = (ObjectNode) new ObjectMapper().readTree("""
                {"us-gaap":{"Assets":{"units":{"USD":[{"end":"2024-12-31","val":1,"accn":"g-1","form":"10-K","filed":"2025-02-01"}]}}},
                 "ifrs-full":{"Assets":{"units":{"USD":[{"end":"2025-12-31","val":2,"accn":"i-1","form":"20-F","filed":"2026-03-01"}]}}}}
                """);

        assertThat(SecFilingSupplement.coveredFilings(facts, false)).containsOnlyKeys("g-1");
        assertThat(SecFilingSupplement.coveredFilings(facts, true)).containsOnlyKeys("g-1", "i-1");
    }
}
