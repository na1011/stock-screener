package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 재시도 간격 — 운영 리미터 설정(분당 300)으로 503만 주는 서버에 companyfacts를 받는다. */
class R16SpacingTest {
    @Test
    void spacing() throws Exception {
        String tickers = new String(new ClassPathResource("fixtures/sec-edgar/tickers.json").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        List<Long> at = new ArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (request.getPath().contains("company_tickers") || request.getPath().contains("tickers")) {
                        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(tickers);
                    }
                    synchronized (at) { at.add(System.nanoTime()); }
                    return new MockResponse().setResponseCode(503);
                }
            });
            server.start();
            DataSourceProperties properties = new DataSourceProperties();
            properties.getSecEdgar().setApiKey("Test Runner test@example.com");
            ExternalApiClientFactory factory = new ExternalApiClientFactory();
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 300, 0);
            String base = server.url("/").toString();
            SecEdgarFinancialAdapter adapter = new SecEdgarFinancialAdapter(factory, properties, limiter,
                    new SecTickerCikCache(factory, properties, limiter, base, 1), base);
            String result;
            try {
                adapter.fetchReports(new SecurityRef(1L, "US", "FIX"), 2020);
                result = "OK";
            } catch (DataSourceException e) {
                result = "THROW " + e.kind();
            }
            StringBuilder sb = new StringBuilder("R16 spacing attempts=" + at.size() + " gapsMs=");
            for (int i = 1; i < at.size(); i++) sb.append((at.get(i) - at.get(i - 1)) / 1_000_000).append(' ');
            sb.append("span=").append(at.size() > 1 ? (at.get(at.size() - 1) - at.get(0)) / 1_000_000 : 0).append("ms -> ").append(result);
            System.out.println(sb);
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("R16_SPACING_OUT")), sb + "\n");
        }
    }
}
