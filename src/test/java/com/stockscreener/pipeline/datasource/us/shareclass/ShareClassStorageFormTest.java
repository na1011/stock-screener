package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장 형태가 판정의 입력을 바꾸지 않는다 — 가지친 companyfacts({@code sec_share_class_input})와 인스턴스 팩트 JSON
 * ({@code sec_filing_class_fact})을 되읽으면 순서까지 같다. 판정이 「처음 나온 구성원·태그」를 고르므로 순서가 값이다.
 */
class ShareClassStorageFormTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INSTANCE = """
            <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                xmlns:us-gaap="http://fasb.org/us-gaap/2024" xmlns:dei="http://xbrl.sec.gov/dei/2024">
              <xbrli:context id="d"><xbrli:entity><xbrli:identifier scheme="http://www.sec.gov/CIK">0000000042</xbrli:identifier>
                <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassBMember</xbrldi:explicitMember></xbrli:segment>
                </xbrli:entity><xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
              <xbrli:context id="a"><xbrli:entity><xbrli:identifier scheme="http://www.sec.gov/CIK">0000000042</xbrli:identifier>
                <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassAMember</xbrldi:explicitMember></xbrli:segment>
                </xbrli:entity><xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
              <xbrli:context id="c"><xbrli:entity><xbrli:identifier scheme="http://www.sec.gov/CIK">0000000042</xbrli:identifier>
                <xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementClassOfStockAxis">us-gaap:CommonClassAMember</xbrldi:explicitMember></xbrli:segment>
                </xbrli:entity><xbrli:period><xbrli:instant>2026-04-20</xbrli:instant></xbrli:period></xbrli:context>
              <dei:DocumentType contextRef="c">10-Q</dei:DocumentType>
              <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="d" decimals="-3">2000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
              <us-gaap:WeightedAverageNumberOfSharesOutstandingBasic contextRef="a" decimals="INF">1000</us-gaap:WeightedAverageNumberOfSharesOutstandingBasic>
              <us-gaap:EarningsPerShareBasic contextRef="a" decimals="2">0.1</us-gaap:EarningsPerShareBasic>
              <dei:EntityCommonStockSharesOutstanding contextRef="c" decimals="INF">1234567</dei:EntityCommonStockSharesOutstanding>
              <dei:TradingSymbol contextRef="c">ABC</dei:TradingSymbol>
            </xbrli:xbrl>
            """;

    @Test
    @DisplayName("인스턴스 팩트를 JSON으로 저장했다 되읽으면 순서까지 같다")
    void classFactsRoundTrip() throws Exception {
        ClassFacts read = ClassFactReader.read(
                new java.io.ByteArrayInputStream(INSTANCE.getBytes(StandardCharsets.UTF_8)), 42, "test").facts();

        ClassFacts back = ClassFacts.fromJson(read.toJson());

        assertThat(back).isEqualTo(read);
        assertThat(back.toJson()).isEqualTo(read.toJson());
        assertThat(new ArrayList<>(back.of("WeightedAverageNumberOfSharesOutstandingBasic").keySet()))
                .extracting(ClassFacts.MemberPeriod::member)
                .containsExactly("us-gaap:CommonClassBMember", "us-gaap:CommonClassAMember");
    }

    @Test
    @DisplayName("가지친 companyfacts는 원본과 같은 판정 입력을 낸다 — 공시 목록은 판정이 안 읽는 개념에서만 나온 공시까지")
    void prunedCompanyFactsKeepTheInputs() {
        JsonNode root = MAPPER.readTree("""
                {"facts":{"dei":{"EntityCommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2026-04-20","val":500,"accn":"0002","form":"10-Q","filed":"2026-05-01"}]}}},
                  "us-gaap":{
                    "Revenues":{"units":{"USD":[{"start":"2025-01-01","end":"2025-12-31","val":9,"accn":"0001","form":"10-K","filed":"2026-02-10"}]}},
                    "WeightedAverageNumberOfSharesOutstandingBasic":{"units":{"shares":[
                      {"start":"2026-01-01","end":"2026-03-31","val":480,"accn":"0002","form":"10-Q","filed":"2026-05-01"}]}},
                    "EarningsPerShareBasic":{"units":{"USD/shares":[
                      {"start":"2026-01-01","end":"2026-03-31","val":0.5,"accn":"0002","form":"10-Q","filed":"2026-05-01"}]}}}}}
                """);

        IssuerFacts pruned = IssuerFacts.fromCompanyFacts(IssuerFacts.prune(root));

        assertSameInputs(pruned, IssuerFacts.fromCompanyFacts(root));
        assertThat(pruned.filings()).containsKeys("0001", "0002");
    }

    @Test
    @DisplayName("응답 루트가 아닌 노드(facts)를 가지치면 빈 트리로 저장하지 않고 던진다")
    void pruneRefusesTheFactsNode() {
        JsonNode root = MAPPER.readTree("""
                {"facts":{"us-gaap":{"Revenues":{"units":{"USD":[]}}}}}
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> IssuerFacts.prune(root.path("facts")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("us-gaap가 없는 정상 응답(dei만 · 펀드)은 던지지 않고 공시 0으로 가지친다 — 던지면 그 종목의 재무 적재가 통째로 실패한다")
    void pruneKeepsAResponseWithoutUsGaap() {
        JsonNode root = MAPPER.readTree("""
                {"cik":1,"facts":{"dei":{"EntityCommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2026-04-20","val":500,"accn":"0002","form":"N-CSR","filed":"2026-05-01"}]}}}}}
                """);

        IssuerFacts pruned = IssuerFacts.fromCompanyFacts(IssuerFacts.prune(root));

        assertThat(pruned.filings()).isEmpty();
        assertThat(pruned.anchorCandidates()).isEmpty();
    }

    /** 저장한 재료 전부(드라이런 재료 폴더 · {@code SHARE_CLASS_DIR})로 같은 성질을 묻는다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "SHARE_CLASS_DIR", matches = ".+")
    void storedMaterialsRoundTrip() throws Exception {
        Path dir = Path.of(System.getenv("SHARE_CLASS_DIR"));
        int companies = 0;
        try (Stream<Path> files = Files.list(dir.resolve("tcf"))) {
            for (Path p : files.toList()) {
                JsonNode root = MAPPER.readTree(p.toFile());
                JsonNode stored = MAPPER.readTree(MAPPER.writeValueAsString(IssuerFacts.prune(root)));
                assertSameInputs(IssuerFacts.fromCompanyFacts(stored), IssuerFacts.fromCompanyFacts(root));
                companies++;
            }
        }
        int instances = 0;
        try (Stream<Path> files = Files.list(dir.resolve("tinst"))) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                long cik = Long.parseLong(name.substring(0, name.indexOf('_')));
                ClassFacts read;
                try (InputStream in = Files.newInputStream(p)) {
                    read = ClassFactReader.read(in, cik, name).facts();
                }
                ClassFacts back = ClassFacts.fromJson(read.toJson());
                assertThat(back).as(name).isEqualTo(read);
                assertThat(back.toJson()).as(name).isEqualTo(read.toJson());
                instances++;
            }
        }
        System.out.printf("share-class storage round trip: companyfacts=%d instances=%d%n", companies, instances);
        assertThat(companies).isPositive();
        assertThat(instances).isPositive();
    }

    private static void assertSameInputs(IssuerFacts actual, IssuerFacts expected) {
        assertThat(entries(actual.filings())).isEqualTo(entries(expected.filings()));
        assertThat(entries(actual.weighted())).isEqualTo(entries(expected.weighted()));
        assertThat(entries(actual.eps())).isEqualTo(entries(expected.eps()));
        assertThat(entries(actual.declaredNumerator())).isEqualTo(entries(expected.declaredNumerator()));
        assertThat(entries(actual.proxyNumerator())).isEqualTo(entries(expected.proxyNumerator()));
        assertThat(entries(actual.coverShares())).isEqualTo(entries(expected.coverShares()));
    }

    private static <K, V> List<Map.Entry<K, V>> entries(Map<K, V> map) {
        return new ArrayList<>(new LinkedHashMap<>(map).entrySet());
    }
}
