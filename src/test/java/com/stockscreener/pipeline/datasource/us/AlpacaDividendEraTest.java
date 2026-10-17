package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배당이 기호 시대마다 오는가(ADR-0180). 이 소스는 배당을 <b>그때의 기호</b> 아래 둔다 — 개명된 행이 지금 기호 하나로만 물으면 개명 전
 * 배당을 잃는다(★15 FGNXP→FGCPP). 신원 판정이 표지를 바꾸면서 남긴 기호 시대로 옛 기호도 묻는다.
 */
class AlpacaDividendEraTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = URLDecoder.decode(request.getPath(), StandardCharsets.UTF_8);
                String body;
                if (path.contains("symbols=FGNXP")) {
                    // 옛 기호 시절 둘 + 경계 뒤 하나(그 기호를 다른 실체가 쓴 때일 수 있다 — 버린다)
                    body = events("FGNXP", "2026-03-15", "2026-06-15", "2026-09-20");
                } else if (path.contains("symbols=FGCPP")) {
                    body = events("FGCPP", "2026-09-15");
                } else {
                    body = "{\"corporate_actions\":{},\"next_page_token\":null}";
                }
                return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("개명된 행 — 옛 기호의 시대 안 배당 + 지금 기호의 시대 안 배당")
    void collectsAcrossSymbolEras() throws Exception {
        SecurityRef renamed = new SecurityRef(1L, "US", "FGCPP", new SecurityRef.Identity("FGNXP", LocalDate.of(2026, 8, 28),
                null, null, null, List.of(new SecurityRef.SymbolEra("FGNXP", null, LocalDate.of(2026, 9, 10)))));

        List<GovernanceReportRow> rows = adapter().fetchReport(renamed, GovernanceEndpoint.DIVIDEND, 2026, "x");

        assertThat(rows).extracting(GovernanceReportRow::publishedAt)
                .containsExactly(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 15), LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("개명 아닌 행 — 지금 기호 하나를 경계 없이 묻는다(지금까지와 같다)")
    void unrenamedRowAsksOneSymbol() throws Exception {
        List<GovernanceReportRow> rows = adapter().fetchReport(new SecurityRef(2L, "US", "FGNXP"),
                GovernanceEndpoint.DIVIDEND, 2026, "x");

        assertThat(rows).hasSize(3);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private AlpacaDividendAdapter adapter() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey("k");
        properties.getAlpaca().setSecretKey("s");
        return new AlpacaDividendAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0), server.url("/").toString(), 50);
    }

    private static String events(String symbol, String... exDates) {
        StringBuilder sb = new StringBuilder("{\"corporate_actions\":{\"cash_dividends\":[");
        for (int i = 0; i < exDates.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(symbol).append(i).append("\",\"symbol\":\"").append(symbol)
                    .append("\",\"ex_date\":\"").append(exDates[i]).append("\",\"rate\":0.5,\"special\":false}");
        }
        return sb.append("]},\"next_page_token\":null}").toString();
    }
}
