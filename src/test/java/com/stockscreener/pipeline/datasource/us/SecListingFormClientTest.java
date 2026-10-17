package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래소 양식 받기 — 고정물은 SEC가 실제로 준 본문 그대로다(HTML은 끝에 끼운 스크립트 · 앞에 SGML 머리가 있다 · 2026-10-18).
 */
class SecListingFormClientTest {

    private static final String DIR = "/fixtures/sec-edgar/listing/";

    private MockWebServer server;
    private final Map<String, byte[]> bodyOf = new ConcurrentHashMap<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private SecListingFormClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                paths.add(request.getPath());
                byte[] body = bodyOf.get(request.getPath());
                if (body == null) {
                    return new MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchKey</Code></Error>");
                }
                return new MockResponse().setResponseCode(200).setBody(new Buffer().write(body));
            }
        });
        server.start();
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 0, 0);
        String base = server.url("/").toString();
        SecFilingClient.Backoff noWait = new SecFilingClient.Backoff(SecFilingClient.Backoff.STANDARD_MILLIS, millis -> { });
        SecFilingClient filings = new SecFilingClient(factory, properties, limiter, base, base);
        SecDailyIndexClient daily = new SecDailyIndexClient(factory, properties, limiter, base, noWait);
        client = new SecListingFormClient(daily, filings, factory, properties, limiter, base, noWait);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void htmlDocumentPassesTheSizeCheckOnceTheInjectedScriptIsRemoved() throws Exception {
        byte[] served = fixture("leu-25-2025-12-11.htm");
        folder(1065059, "0001628280-25-056420", Map.of("form25_20251211.htm", 16613L));
        bodyOf.put("/Archives/edgar/data/1065059/000162828025056420/form25_20251211.htm", served);

        byte[] doc = client.document(1065059, "0001628280-25-056420", "25").orElseThrow();

        assertThat(served.length).isEqualTo(16725);
        assertThat(doc.length).isEqualTo(16613);
        assertThat(new String(doc, StandardCharsets.ISO_8859_1)).doesNotContain("<script type=\"text/javascript\"  src=");
        assertThat(SecListingFormClient.typeOf(doc)).isEqualTo("25");
    }

    @Test
    void truncatedDocumentIsAFetchFailureNotARead() throws Exception {
        byte[] served = fixture("leu-25-2025-12-11.htm");
        folder(1065059, "0001628280-25-056420", Map.of("form25_20251211.htm", 16613L));
        bodyOf.put("/Archives/edgar/data/1065059/000162828025056420/form25_20251211.htm",
                java.util.Arrays.copyOf(served, 9000));

        assertThatThrownBy(() -> client.document(1065059, "0001628280-25-056420", "25"))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
    }

    @Test
    void exchangeRemovalTakesThePrimaryXml() throws Exception {
        byte[] xml = fixture("two-25nse-2026-08-25.xml");
        folder(1465740, "0000876661-26-000705", Map.of("primary_doc.xml", (long) xml.length, "ruleprovisionnotice.htm", 1335L));
        bodyOf.put("/Archives/edgar/data/1465740/000087666126000705/primary_doc.xml", xml);

        assertThat(client.document(1465740, "0000876661-26-000705", "25-NSE")).hasValueSatisfying(
                doc -> assertThat(doc).isEqualTo(xml));
        assertThat(paths).noneMatch(p -> p.endsWith("ruleprovisionnotice.htm"));
    }

    @Test
    void severalHtmlDocumentsAreChosenByTheirTypeHeader() throws Exception {
        byte[] form = fixture("bmnr-8a12b-2026-04-08.htm");
        byte[] exhibit = "<DOCUMENT>\n<TYPE>EX-3.1\n<TEXT>\n<html><body>charter</body></html>\n</TEXT>\n</DOCUMENT>\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        folder(1829311, "0001493152-26-015645", Map.of("ex3-1.htm", (long) exhibit.length, "form8a12b.htm", 22632L));
        bodyOf.put("/Archives/edgar/data/1829311/000149315226015645/ex3-1.htm", exhibit);
        bodyOf.put("/Archives/edgar/data/1829311/000149315226015645/form8a12b.htm", form);

        byte[] doc = client.document(1829311, "0001493152-26-015645", "8-A12B").orElseThrow();

        assertThat(SecListingFormClient.typeOf(doc)).isEqualTo("8-A12B");
    }

    @Test
    void noPrimaryDocumentIsEmptyNotGuessed() throws Exception {
        folder(1465740, "0000876661-26-000705", Map.of("ruleprovisionnotice.htm", 1335L));

        assertThat(client.document(1465740, "0000876661-26-000705", "25-NSE")).isEmpty();
    }

    private void folder(long cik, String accession, Map<String, Long> items) {
        StringBuilder json = new StringBuilder("{\"directory\":{\"item\":[");
        json.append("{\"name\":\"").append(accession).append("-index.html\",\"size\":\"\"}");
        items.forEach((name, size) -> json.append(",{\"name\":\"").append(name).append("\",\"size\":\"").append(size).append("\"}"));
        json.append("]}}");
        bodyOf.put("/Archives/edgar/data/%d/%s/index.json".formatted(cik, accession.replace("-", "")),
                json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(DIR + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }
}
