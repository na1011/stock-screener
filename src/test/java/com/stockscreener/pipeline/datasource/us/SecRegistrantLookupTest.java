package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.RegistrantLookup;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.IndexLine;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등록인 조회의 SEC 구현 — 범주 판정(밖 · 원장 갈림 · 보통주 아님)과 후보 출처. 받기는 가짜이고 제거 양식 본문은 실제 고정물이다.
 */
class SecRegistrantLookupTest {

    private static final LocalDate E = LocalDate.of(2026, 7, 31);

    private final StubClient sec = new StubClient();

    @Test
    void removalFormIssuerIsBoundWithoutAName() throws Exception {
        sec.lines.add(new IndexLine("25-NSE", 712515L, E, "0001354457-26-000757", "ELECTRONIC ARTS INC."));
        sec.docs.put("0001354457-26-000757", fixture("ea-25nse-2026-08-04.xml"));
        sec.covers.put(712515L, cover(Map.of("EA", "NASDAQ"), "Common Stock, $0.01 par value"));

        RegistrantLookup.Result r = new SecRegistrantLookup(sec).open(E, E).find("EA", E, Optional.empty(), List.of());

        assertThat(r.outcome()).isEqualTo(RegistrantLookup.Outcome.BOUND);
        assertThat(r.exchange()).isEqualTo("NASDAQ");
        assertThat(r.name()).isEqualTo("ELECTRONIC ARTS INC. Common Stock, $0.01 par value");
    }

    /** ORLA — 표지는 NYSE인데 효력일 무렵 제거 양식은 NYSE American이다(원장 갈림 · 탐색 ★17). 안 들이고 센다. */
    @Test
    void coverAndRemovalFormDisagreeIsASplitLedger() throws Exception {
        sec.lines.add(new IndexLine("25-NSE", 1680056L, E, "0001143313-26-000045", "Orla Mining Ltd."));
        sec.docs.put("0001143313-26-000045", fixture("orla-25nse-2026-07-31.xml"));
        sec.covers.put(1680056L, cover(Map.of("ORLA", "NYSE"), "Common Shares, no par value"));

        RegistrantLookup.Result r = new SecRegistrantLookup(sec).open(E, E).find("ORLA", E, Optional.empty(), List.of());

        assertThat(r.outcome()).isEqualTo(RegistrantLookup.Outcome.SPLIT_LEDGER);
        assertThat(r.cik()).isEqualTo(1680056L);
    }

    /** NGD — 표지가 NYSE American이라 범주 밖이다. */
    @Test
    void coverOnNyseAmericanIsOutside() throws Exception {
        sec.lines.add(new IndexLine("40-F", 800166L, E.minusDays(100), "a", "New Gold Inc. /FI"));
        sec.covers.put(800166L, cover(Map.of("NGD", "NYSEAMER"), "Common Shares"));

        RegistrantLookup.Result r = new SecRegistrantLookup(sec).open(E, E).find("NGD", E, Optional.of("New Gold Inc."), List.of());

        assertThat(r.outcome()).isEqualTo(RegistrantLookup.Outcome.OUTSIDE);
    }

    @Test
    void warrantLineIsNotCommon() throws Exception {
        sec.lines.add(new IndexLine("10-Q", 42L, E.minusDays(30), "b", "Some Acquisition Corp"));
        sec.covers.put(42L, cover(Map.of("SACW", "NASDAQ"), "Redeemable warrants, each exercisable for one share"));

        RegistrantLookup.Result r = new SecRegistrantLookup(sec).open(E, E)
                .find("SACW", E, Optional.of("Some Acquisition Corp Warrant"), List.of());

        assertThat(r.outcome()).isEqualTo(RegistrantLookup.Outcome.NOT_COMMON);
    }

    @Test
    void removalFormsFarFromTheDateAreNotCandidates() throws Exception {
        sec.lines.add(new IndexLine("25-NSE", 712515L, E.minusDays(5), "0001354457-26-000757", "ELECTRONIC ARTS INC."));
        sec.covers.put(712515L, cover(Map.of("EA", "NASDAQ"), "Common Stock"));

        RegistrantLookup.Result r = new SecRegistrantLookup(sec).open(E, E).find("EA", E, Optional.empty(), List.of());

        assertThat(r.outcome()).isEqualTo(RegistrantLookup.Outcome.NONE);
        assertThat(sec.coverAsked).isEmpty();
    }

    private static ClassFacts cover(Map<String, String> symbolToExchange, String title) {
        Map<String, LinkedHashMap<String, String>> cover = new LinkedHashMap<>();
        LinkedHashMap<String, String> symbols = new LinkedHashMap<>();
        LinkedHashMap<String, String> exchanges = new LinkedHashMap<>();
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        symbolToExchange.forEach((symbol, exchange) -> {
            symbols.put(ClassFacts.AXISLESS, symbol);
            exchanges.put(ClassFacts.AXISLESS, exchange);
            titles.put(ClassFacts.AXISLESS, title);
        });
        cover.put(ClassFactReader.TRADING_SYMBOL, symbols);
        cover.put(ClassFactReader.SECURITY_EXCHANGE, exchanges);
        cover.put(ClassFactReader.SECURITY_TITLE, titles);
        return new ClassFacts(new LinkedHashMap<>(), cover, "10-Q");
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sec-edgar/listing/" + name)) {
            return in.readAllBytes();
        }
    }

    /** 받기를 가짜로 — 색인 줄 · 문서 · 표지를 미리 준 대로 낸다(외부 호출 0). */
    private static final class StubClient extends SecListingFormClient {
        final List<IndexLine> lines = new ArrayList<>();
        final Map<String, byte[]> docs = new HashMap<>();
        final Map<Long, ClassFacts> covers = new HashMap<>();
        final List<Long> coverAsked = new ArrayList<>();

        StubClient() {
            super(null, null, new ExternalApiClientFactory(), properties(), new TokenBucketRateLimiter("sec-edgar", 0, 0));
        }

        private static DataSourceProperties properties() {
            DataSourceProperties p = new DataSourceProperties();
            p.getSecEdgar().setApiKey("Test Runner test@example.com");
            return p;
        }

        @Override
        public List<IndexLine> lines(LocalDate from, LocalDate to, Set<String> forms) {
            return lines.stream().filter(l -> forms.contains(l.form())).toList();
        }

        @Override
        public List<IndexLine> candidateLines(LocalDate from, LocalDate to, Set<String> forms) {
            return lines(from, to, forms);
        }

        @Override
        public Optional<byte[]> document(long cik, String accession, String form) {
            return Optional.ofNullable(docs.get(accession));
        }

        @Override
        public Optional<Cover> lastCoverBefore(long cik, LocalDate before, LocalDate since) {
            coverAsked.add(cik);
            return Optional.ofNullable(covers.get(cik)).map(c -> new Cover("acc-" + cik, "10-Q", before.minusDays(40), c));
        }
    }
}
