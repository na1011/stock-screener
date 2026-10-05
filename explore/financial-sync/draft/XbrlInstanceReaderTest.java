package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공시 인스턴스를 companyfacts가 싣는 규칙대로 읽는가(ADR-0173). 픽스처는 실측 모양(ABT · AAPL)을 줄인 것이다. */
class XbrlInstanceReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static XbrlInstanceReader.Instance read(long cik) throws Exception {
        try (InputStream in = new ClassPathResource("fixtures/sec-edgar/instance-mini.xml").getInputStream()) {
            return XbrlInstanceReader.read(in, cik, "test");
        }
    }

    @Test
    @DisplayName("축 없는 us-gaap 수치만 · 회사 택소노미·축 컨텍스트·unitRef 없는 dei·nil은 안 싣는다 — 택소노미는 접두어가 아니라 URI로")
    void keepsWhatCompanyFactsKeeps() throws Exception {
        XbrlInstanceReader.Instance instance = read(1800);
        assertThat(instance.facts()).extracting(XbrlInstanceReader.Fact::concept)
                .containsExactlyInAnyOrder("Revenues", "EarningsPerShareBasic", "Goodwill");
        assertThat(instance.facts()).allMatch(f -> f.taxonomy().equals("us-gaap"));
        assertThat(instance.fiscalYearFocus()).isEqualTo("2026");
        assertThat(instance.fiscalPeriodFocus()).isEqualTo("Q2");
        XbrlInstanceReader.Fact eps = instance.facts().stream()
                .filter(f -> f.concept().equals("EarningsPerShareBasic")).findFirst().orElseThrow();
        assertThat(eps.unit()).isEqualTo("USD/shares");
        assertThat(eps.start()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    @DisplayName("같은 키가 정밀도를 달리해 두 번이면 정밀한 쪽 — ABT 영업권 -6 · -8 실측 모양")
    void choosesTheMostPreciseDuplicate() throws Exception {
        XbrlInstanceReader.Fact goodwill = read(1800).facts().stream()
                .filter(f -> f.concept().equals("Goodwill")).findFirst().orElseThrow();
        assertThat(goodwill.value()).isEqualByComparingTo(new BigDecimal("24035000000"));
        assertThat(goodwill.start()).isNull();
        assertThat(goodwill.end()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    @DisplayName("같은 정밀도에 값이 둘이면 MALFORMED — 어느 쪽을 골라도 근거가 없다")
    void rejectsConflictingDuplicatesOfEqualPrecision() {
        String xml = """
                <xbrl xmlns="http://www.xbrl.org/2003/instance" xmlns:g="http://fasb.org/us-gaap/2026" xmlns:iso4217="http://www.xbrl.org/2003/iso4217">
                  <context id="i"><entity><identifier scheme="http://www.sec.gov/CIK">1800</identifier></entity><period><instant>2025-12-31</instant></period></context>
                  <unit id="u"><measure>iso4217:USD</measure></unit>
                  <g:Assets contextRef="i" unitRef="u" decimals="-6">100000000</g:Assets>
                  <g:Assets contextRef="i" unitRef="u" decimals="-6">101000000</g:Assets>
                </xbrl>""";
        assertThatThrownBy(() -> XbrlInstanceReader.read(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), 1800, "t"))
                .isInstanceOf(DataSourceException.class)
                .hasFieldOrPropertyWithValue("kind", DataSourceException.Kind.MALFORMED)
                .hasMessageContaining("같은 정밀도");
    }

    @Test
    @DisplayName("컨텍스트 식별자가 이 공시의 CIK가 아니면 MALFORMED — 남의 팩트를 우리 행으로 들이지 않는다")
    void rejectsAnotherEntitysContext() {
        assertThatThrownBy(() -> read(320193))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("CIK가 아니다");
    }

    @Test
    @DisplayName("companyfacts 노드 모양으로 합친다 — 이미 같은 accn·기간 슬롯이 있으면 안 더한다")
    void mergesAsCompanyFactsNodesWithoutDuplicatingSlots() throws Exception {
        ObjectNode facts = (ObjectNode) MAPPER.readTree("""
                {"us-gaap":{"Revenues":{"units":{"USD":[
                  {"start":"2026-04-01","end":"2026-06-30","val":11138000000,"accn":"0001628280-26-050134","fy":2026,"fp":"Q2","form":"10-Q","filed":"2026-07-28"}]}}}}""");
        int added = XbrlInstanceReader.mergeInto(facts, read(1800), "0001628280-26-050134", "10-Q",
                LocalDate.of(2026, 7, 28));
        assertThat(added).as("Revenues는 이미 있어 EPS · 영업권 둘만").isEqualTo(2);
        assertThat(facts.path("us-gaap").path("Revenues").path("units").path("USD").size()).isEqualTo(1);
        var eps = facts.path("us-gaap").path("EarningsPerShareBasic").path("units").path("USD/shares").get(0);
        assertThat(eps.path("val").asString("")).isEqualTo("0.53");
        assertThat(eps.path("form").asString("")).isEqualTo("10-Q");
        assertThat(eps.path("filed").asString("")).isEqualTo("2026-07-28");
        assertThat(eps.path("fy").asInt(0)).isEqualTo(2026);
        assertThat(eps.path("fp").asString("")).isEqualTo("Q2");
        assertThat(eps.path("accn").asString("")).isEqualTo("0001628280-26-050134");
    }
}
