package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/** rebut16 탐침 — 출력은 R16 줄(표준 출력)과 R16_OUT 파일. 단언 없음(재는 것). */
class R16ProbeTest {

    static final class CountingLimiter implements RateLimiter {
        int acquires;
        final long quota;
        int interruptAt; // n번째 acquire에서 InterruptedException(0 = 없음)

        CountingLimiter(long quota, int interruptAt) {
            this.quota = quota;
            this.interruptAt = interruptAt;
        }

        @Override
        public void acquire() throws InterruptedException {
            if (interruptAt > 0 && acquires + 1 == interruptAt) {
                interruptAt = 0;
                throw new InterruptedException("probe");
            }
            acquires++;
        }

        @Override
        public boolean hasDailyQuota() {
            return quota <= 0 || acquires < quota;
        }

        @Override
        public long usedToday() {
            return acquires;
        }

        @Override
        public String sourceName() {
            return "sec-edgar";
        }
    }

    private static String fixture(String name) throws Exception {
        return new String(new ClassPathResource("fixtures/sec-edgar/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static MockResponse r(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static List<String> lines = new ArrayList<>();

    private static void out(String s) {
        System.out.println(s);
        lines.add(s);
    }

    private void factsScenario(String name, RateLimiter limiter, Consumer<MockWebServer> enqueue) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(r(200, fixture("tickers.json")));
            enqueue.accept(server);
            DataSourceProperties properties = new DataSourceProperties();
            properties.getSecEdgar().setApiKey("Test Runner test@example.com");
            properties.getSecEdgar().setReadTimeout(java.time.Duration.ofSeconds(3));
            ExternalApiClientFactory factory = new ExternalApiClientFactory();
            String base = server.url("/").toString();
            SecEdgarFinancialAdapter adapter = new SecEdgarFinancialAdapter(factory, properties, limiter,
                    new SecTickerCikCache(factory, properties, limiter, base, 1), base);
            String result;
            try {
                FinancialFetch fetch = adapter.fetchReports(new SecurityRef(1L, "US", "FIX"), 2020);
                result = "OK reports=" + fetch.reports().size();
            } catch (DataSourceException e) {
                result = "THROW " + e.kind() + " msg=" + e.getMessage().replace('\n', ' ')
                        + (e.getCause() == null ? "" : " cause=" + e.getCause().getClass().getSimpleName());
            }
            boolean interrupted = Thread.interrupted();
            out("R16 facts " + name + " calls=" + (server.getRequestCount() - 1) + " acquires=" + limiter.usedToday()
                    + " interruptedAfter=" + interrupted + " -> " + result);
        }
    }

    private void jsonScenario(String name, boolean index, Consumer<MockWebServer> enqueue) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            enqueue.accept(server);
            DataSourceProperties properties = new DataSourceProperties();
            properties.getSecEdgar().setApiKey("Test Runner test@example.com");
            ExternalApiClientFactory factory = new ExternalApiClientFactory();
            String base = server.url("/").toString();
            CountingLimiter limiter = new CountingLimiter(0, 0);
            SecFilingClient client = new SecFilingClient(factory, properties, limiter, base, base);
            String result;
            try {
                if (index) {
                    result = "OK items=" + client.folder(1800, "0000001800-26-000020").size();
                } else {
                    result = "OK filings=" + client.filings(1800, LocalDate.of(2000, 1, 1)).size();
                }
            } catch (DataSourceException e) {
                result = "THROW " + e.kind();
            }
            out("R16 json " + (index ? "index " : "submissions ") + name + " calls=" + server.getRequestCount()
                    + " acquires=" + limiter.usedToday() + " -> " + result);
        }
    }

    @Test
    void probeFetchPaths() throws Exception {
        String full = fixture("companyfacts.json");
        String half = full.substring(0, full.length() / 2);
        factsScenario("S01 200", new CountingLimiter(0, 0), s -> s.enqueue(r(200, full)));
        factsScenario("S02 404", new CountingLimiter(0, 0), s -> { s.enqueue(r(404, "<Error/>")); s.enqueue(r(200, full)); });
        factsScenario("S03 429", new CountingLimiter(0, 0), s -> { s.enqueue(r(429, "")); s.enqueue(r(200, full)); });
        factsScenario("S04 403", new CountingLimiter(0, 0), s -> { s.enqueue(r(403, "<html/>")); s.enqueue(r(200, full)); });
        factsScenario("S05 400", new CountingLimiter(0, 0), s -> { s.enqueue(r(400, "")); s.enqueue(r(200, full)); });
        factsScenario("S06 503x3", new CountingLimiter(0, 0), s -> { for (int i = 0; i < 3; i++) s.enqueue(r(503, "")); s.enqueue(r(200, full)); });
        factsScenario("S07 503,200", new CountingLimiter(0, 0), s -> { s.enqueue(r(503, "")); s.enqueue(r(200, full)); });
        factsScenario("S08 half,200", new CountingLimiter(0, 0), s -> { s.enqueue(r(200, half)); s.enqueue(r(200, full)); });
        factsScenario("S09 half x3", new CountingLimiter(0, 0), s -> { for (int i = 0; i < 3; i++) s.enqueue(r(200, half)); s.enqueue(r(200, full)); });
        factsScenario("S10 {} (no facts),200", new CountingLimiter(0, 0), s -> { s.enqueue(r(200, "{}")); s.enqueue(r(200, full)); });
        factsScenario("S11 empty,200", new CountingLimiter(0, 0), s -> { s.enqueue(r(200, "")); s.enqueue(r(200, full)); });
        factsScenario("S12 spaces,200", new CountingLimiter(0, 0), s -> { s.enqueue(r(200, "   ")); s.enqueue(r(200, full)); });
        factsScenario("S13 html,200", new CountingLimiter(0, 0), s -> { s.enqueue(r(200, "<html>maint</html>")); s.enqueue(r(200, full)); });
        factsScenario("S14 half,503,503", new CountingLimiter(0, 0), s -> { s.enqueue(r(200, half)); s.enqueue(r(503, "")); s.enqueue(r(503, "")); });
        factsScenario("S15 503,503,half", new CountingLimiter(0, 0), s -> { s.enqueue(r(503, "")); s.enqueue(r(503, "")); s.enqueue(r(200, half)); });
        // quota: tickers 1 + companyfacts 2 → 셋째 시도에서 한도
        factsScenario("S16 quota3 503,503,200", new CountingLimiter(3, 0), s -> { s.enqueue(r(503, "")); s.enqueue(r(503, "")); s.enqueue(r(200, full)); });
        // 2번째 acquire(= companyfacts 첫 시도)에서 인터럽트 — 가짜 리미터는 다음 acquire를 통과시킨다
        factsScenario("S17 interrupt@cf1 (fake limiter)", new CountingLimiter(0, 2), s -> s.enqueue(r(200, full)));
        factsScenario("S18 reset,200", new CountingLimiter(0, 0), s -> {
            s.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            s.enqueue(r(200, full));
        });
        factsScenario("S19 cut-mid-body(fixed length),200", new CountingLimiter(0, 0), s -> {
            s.enqueue(r(200, full).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            s.enqueue(r(200, full));
        });
        factsScenario("S20 404 x1 only", new CountingLimiter(0, 0), s -> s.enqueue(r(404, "<Error/>")));
    }

    @Test
    void probeInterruptRealLimiter() throws Exception {
        // 실제 리미터(분당 60 = 1초 간격): 매핑 acquire 직후 companyfacts acquire가 약 1초 잔다 → 200ms 뒤 인터럽트.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 60, 0);
        Thread me = Thread.currentThread();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                return;
            }
            me.interrupt();
        });
        t.start();
        long start = System.nanoTime();
        String full = fixture("companyfacts.json");
        factsScenario("S21 interrupt during real-limiter sleep", limiter, s -> { s.enqueue(r(200, full)); s.enqueue(r(200, full)); s.enqueue(r(200, full)); });
        out("R16 facts S21 elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
        t.join();
    }

    @Test
    void probeJsonPaths() throws Exception {
        String index = "{\"directory\":{\"item\":[{\"name\":\"a_htm.xml\",\"size\":\"10\"}]}}";
        String sub = "{\"filings\":{\"recent\":{\"accessionNumber\":[],\"form\":[],\"filingDate\":[],\"reportDate\":[],\"isXBRL\":[]},\"files\":[]}}";
        for (boolean idx : new boolean[] {true, false}) {
            String ok = idx ? index : sub;
            String half = ok.substring(0, ok.length() / 2);
            jsonScenario("J01 200", idx, s -> s.enqueue(r(200, ok)));
            jsonScenario("J02 404,200", idx, s -> { s.enqueue(r(404, "<Error/>")); s.enqueue(r(200, ok)); });
            jsonScenario("J03 429,200", idx, s -> { s.enqueue(r(429, "")); s.enqueue(r(200, ok)); });
            jsonScenario("J04 403,200", idx, s -> { s.enqueue(r(403, "")); s.enqueue(r(200, ok)); });
            jsonScenario("J05 half,200", idx, s -> { s.enqueue(r(200, half)); s.enqueue(r(200, ok)); });
            jsonScenario("J06 half x3", idx, s -> { for (int i = 0; i < 3; i++) s.enqueue(r(200, half)); s.enqueue(r(200, ok)); });
            jsonScenario("J07 spaces,200", idx, s -> { s.enqueue(r(200, "  ")); s.enqueue(r(200, ok)); });
            jsonScenario("J08 {} ,200", idx, s -> { s.enqueue(r(200, "{}")); s.enqueue(r(200, ok)); });
        }
    }

    /** 실물 companyfacts를 보충 없는 어댑터에 먹여 FinancialFetch의 해시를 찍는다(v10·v11 같은 시험). */
    @Test
    void byteEquality() throws Exception {
        String dir = System.getenv("R16_CF_DIR");
        String sample = System.getenv("R16_SAMPLE");
        String outPath = System.getenv("R16_OUT");
        if (dir == null || sample == null || outPath == null) {
            return;
        }
        List<String> rows = new ArrayList<>();
        for (String ticker : Files.readAllLines(Path.of(sample))) {
            if (ticker.isBlank()) {
                continue;
            }
            String body = Files.readString(Path.of(dir, ticker + ".json"));
            long cik = new tools.jackson.databind.ObjectMapper().readTree(body).path("cik").asLong();
            String tickers = "{\"fields\":[\"cik\",\"name\",\"ticker\",\"exchange\"],\"data\":[[" + cik + ",\"x\",\""
                    + ticker + "\",\"NYSE\"]]}";
            try (MockWebServer server = new MockWebServer()) {
                server.start();
                server.enqueue(r(200, tickers));
                server.enqueue(r(200, body));
                DataSourceProperties properties = new DataSourceProperties();
                properties.getSecEdgar().setApiKey("Test Runner test@example.com");
                ExternalApiClientFactory factory = new ExternalApiClientFactory();
                String base = server.url("/").toString();
                CountingLimiter limiter = new CountingLimiter(0, 0);
                SecEdgarFinancialAdapter adapter = new SecEdgarFinancialAdapter(factory, properties, limiter,
                        new SecTickerCikCache(factory, properties, limiter, base, 1), base);
                String result;
                try {
                    FinancialFetch fetch = adapter.fetchReports(new SecurityRef(1L, "US", ticker), 2018);
                    result = "OK " + fetch.reports().size() + " " + HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(String.valueOf(fetch).getBytes(StandardCharsets.UTF_8)));
                } catch (DataSourceException e) {
                    result = "THROW " + e.kind();
                }
                rows.add(ticker + "\t" + (server.getRequestCount() - 1) + "\t" + result);
            }
        }
        Files.write(Path.of(outPath), rows);
    }

    @org.junit.jupiter.api.AfterAll
    static void dump() throws Exception {
        String p = System.getenv("R16_PROBE_OUT");
        if (p != null) {
            Files.write(Path.of(p), lines);
        }
    }
}
