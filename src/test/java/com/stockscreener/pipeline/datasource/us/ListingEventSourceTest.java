package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.pipeline.datasource.us.listing.ListedTimeline;
import com.stockscreener.pipeline.datasource.us.listing.ListingEvent;
import com.stockscreener.pipeline.datasource.us.listing.Venue;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 일일 색인 → 양식 본문 → 사건 → 범주 이력까지 배선(LEU · 실제 공시 본문). 남의 CIK 줄은 문서를 안 받는다.
 */
class ListingEventSourceTest {

    private static final long LEU = 1065059L;
    private static final String DIR = "/fixtures/sec-edgar/listing/";

    private MockWebServer server;
    private final Map<String, byte[]> bodyOf = new ConcurrentHashMap<>();
    private final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    private ListingEventSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                paths.add(request.getPath());
                byte[] body = bodyOf.get(request.getPath());
                return body == null ? new MockResponse().setResponseCode(404).setBody("missing")
                        : new MockResponse().setResponseCode(200).setBody(new Buffer().write(body));
            }
        });
        server.start();
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 0, 0);
        String base = server.url("/").toString();
        SecFilingClient.Backoff noWait = new SecFilingClient.Backoff(SecFilingClient.Backoff.STANDARD_MILLIS, millis -> { });
        SecDailyIndexClient daily = new SecDailyIndexClient(factory, properties, limiter, base, noWait);
        source = new ListingEventSource(new SecListingFormClient(daily, new SecFilingClient(factory, properties, limiter, base, base),
                factory, properties, limiter, base, noWait));

        dayIndex(LocalDate.of(2025, 12, 3), List.of(
                line("8-A12B", "CENTRUS ENERGY CORP", LEU, "20251203", "0001628280-25-054898"),
                line("8-A12B", "SOMEONE ELSE INC", 999L, "20251203", "0000000999-25-000001")));
        dayIndex(LocalDate.of(2025, 12, 11), List.of(
                line("25", "CENTRUS ENERGY CORP", LEU, "20251211", "0001628280-25-056420")));
        bodyOf.put("/Archives/edgar/daily-index/2025/QTR4/index.json", quarter(Map.of(
                LocalDate.of(2025, 12, 3), kb("2025-12-03"), LocalDate.of(2025, 12, 11), kb("2025-12-11"))));
        document(LEU, "0001628280-25-054898", "form8-a_20251203.htm", "leu-8a12b-2025-12-03.htm", 33963);
        document(LEU, "0001628280-25-056420", "form25_20251211.htm", "leu-25-2025-12-11.htm", 16613);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void leuUplistingBecomesAnEntryOnTheTimeline() throws Exception {
        ListingEventSource.Result result = source.events(Set.of(LEU), LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31));

        assertThat(result.forms()).isEqualTo(2);
        assertThat(result.unreadable()).isEmpty();
        List<ListingEvent> events = result.byCik().get(LEU);
        assertThat(events).extracting(ListingEvent::venue)
                .containsExactlyInAnyOrder(new Venue("NYSE", true), new Venue("NYSE American", false));
        ListedTimeline leu = ListedTimeline.of(LocalDate.of(2026, 8, 28), true, events, 30);
        assertThat(leu.on(LocalDate.of(2025, 11, 28))).isEqualTo(ListedTimeline.Verdict.UNLISTED);
        assertThat(leu.on(LocalDate.of(2025, 12, 5))).isEqualTo(ListedTimeline.Verdict.LISTED);
        assertThat(paths).noneMatch(p -> p.contains("/999/"));
    }

    private void document(long cik, String accession, String name, String fixture, long size) throws IOException {
        String folder = accession.replace("-", "");
        bodyOf.put("/Archives/edgar/data/%d/%s/index.json".formatted(cik, folder), ("{\"directory\":{\"item\":["
                + "{\"name\":\"" + accession + "-index.html\",\"size\":\"\"},"
                + "{\"name\":\"" + accession + ".txt\",\"size\":\"\"},"
                + "{\"name\":\"" + name + "\",\"size\":\"" + size + "\"}]}}").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = getClass().getResourceAsStream(DIR + fixture)) {
            bodyOf.put("/Archives/edgar/data/%d/%s/%s".formatted(cik, folder, name), in.readAllBytes());
        }
    }

    private final Map<String, byte[]> dayBodies = new ConcurrentHashMap<>();

    private void dayIndex(LocalDate day, List<String> lines) {
        String body = "Description: Daily Index\n\nForm Type   Company Name   CIK   Date Filed  File Name\n"
                + "-".repeat(100) + "\n" + String.join("\n", lines) + "\n";
        byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        dayBodies.put(day.toString(), bytes);
        bodyOf.put("/Archives/edgar/daily-index/%d/QTR4/form.%s.idx".formatted(day.getYear(),
                day.toString().replace("-", "")), bytes);
    }

    private int kb(String day) {
        return (int) ((dayBodies.get(day).length + 1023L) / 1024L);
    }

    private static String line(String form, String company, long cik, String filed, String accession) {
        return "%-17s%-62s%-12d%-12sedgar/data/%d/%s.txt".formatted(form, company, cik, filed, cik, accession);
    }

    private static byte[] quarter(Map<LocalDate, Integer> days) {
        StringBuilder json = new StringBuilder("{\"directory\":{\"item\":[");
        String items = days.entrySet().stream().map(e -> "{\"name\":\"form.%s.idx\",\"size\":\"%d KB\"}"
                .formatted(e.getKey().toString().replace("-", ""), e.getValue())).reduce((a, b) -> a + "," + b).orElse("");
        return json.append(items).append("]}}").toString().getBytes(StandardCharsets.UTF_8);
    }
}
