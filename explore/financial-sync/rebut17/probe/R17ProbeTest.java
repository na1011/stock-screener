package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** rebut17 탐침 — 단언 없음(재는 것). 출력은 R17 줄과 R17_PROBE_OUT 파일. */
class R17ProbeTest {

    static final class CountingLimiter implements RateLimiter {
        int acquires;

        @Override
        public void acquire() {
            acquires++;
        }

        @Override
        public boolean hasDailyQuota() {
            return true;
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

    private static final List<String> lines = new ArrayList<>();

    private static void out(String s) {
        System.out.println("R17 " + s);
        lines.add(s);
    }

    private static MockResponse r(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    private static final String SUBMISSIONS = "{\"filings\":{\"recent\":{\"accessionNumber\":[],\"form\":[],"
            + "\"filingDate\":[],\"reportDate\":[],\"isXBRL\":[]},\"files\":[]}}";

    /**
     * 공시 목록(data 호스트)은 살아 있고 공시 폴더(files 호스트)만 503인 날을 종목 다섯으로 흉내 낸다 — 보충의 실제 순서(종목마다
     * filings → 대상 공시마다 folder)대로. interleave=false는 대조군(공시 목록 없이 folder만 연달아 — 차단이 열리는 것을 본다).
     */
    private void hostSplit(String name, boolean interleave, int targetsPerTicker) throws Exception {
        try (MockWebServer data = new MockWebServer(); MockWebServer files = new MockWebServer()) {
            data.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return r(200, SUBMISSIONS);
                }
            });
            files.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return r(503, "<html>down</html>");
                }
            });
            data.start();
            files.start();
            DataSourceProperties properties = new DataSourceProperties();
            properties.getSecEdgar().setApiKey("Test Runner test@example.com");
            List<Long> pauses = new ArrayList<>();
            SecFilingClient.Backoff backoff = new SecFilingClient.Backoff(SecFilingClient.Backoff.STANDARD_MILLIS, pauses::add);
            SecFilingClient client = new SecFilingClient(new ExternalApiClientFactory(), properties, new CountingLimiter(),
                    data.url("/").toString(), files.url("/").toString(), backoff);
            long total = 0;
            for (int t = 1; t <= 5; t++) {
                int before = pauses.size();
                if (interleave) {
                    client.filings(320193L + t, LocalDate.of(2018, 1, 1));
                }
                int failed = 0;
                for (int k = 1; k <= targetsPerTicker; k++) {
                    try {
                        client.folder(320193L + t, "0000320193-26-00000" + k);
                    } catch (DataSourceException e) {
                        failed++;
                    }
                }
                long waited = pauses.subList(before, pauses.size()).stream().mapToLong(Long::longValue).sum();
                total += waited;
                out(name + " ticker" + t + " targets=" + targetsPerTicker + " failed=" + failed + " pauses="
                        + pauses.subList(before, pauses.size()) + " waited_ms=" + waited);
            }
            out(name + " TOTAL waited_ms=" + total + " files_requests=" + files.getRequestCount());
            // 다음 실행(같은 빈 — 싱글턴이라 상태가 남는다): 첫 콜이 실패하면 대기하는가
            int before = pauses.size();
            try {
                client.folder(1L, "0000000001-26-000001");
            } catch (DataSourceException e) {
                // 잰다
            }
            out(name + " NEXT_RUN_FIRST_FAIL pauses=" + pauses.subList(before, pauses.size()));
        }
    }

    @Test
    void probeHostSplit() throws Exception {
        hostSplit("split-interleaved", true, 2);
        hostSplit("split-interleaved", true, 4);
        hostSplit("control-files-only", false, 2);
    }

    /** 실물 companyfacts를 보충 없는 어댑터에 먹여 FinancialFetch의 해시를 찍는다(rebut16 byteEquality와 같은 글자). */
    @Test
    void byteEquality() throws Exception {
        String dir = System.getenv("R17_CF_DIR");
        String sample = System.getenv("R17_SAMPLE");
        String outPath = System.getenv("R17_OUT");
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
                CountingLimiter limiter = new CountingLimiter();
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

    @AfterAll
    static void dump() throws Exception {
        String p = System.getenv("R17_PROBE_OUT");
        if (p != null) {
            Files.write(Path.of(p), lines);
        }
    }
}
