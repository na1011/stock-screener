package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이 어댑터의 존재 이유를 지킨다 — 없으면 {@code StockRegistrar}가 <b>이름을 티커로 채우고
 * 배치는 성공으로 끝난다.</b>
 *
 * <p>가장 중요한 시험은 <b>캐시 적중일 때 호출이 0건</b>이라는 것이다. 유니버스 응답이 이미
 * 이름을 주므로 추가 콜이 있으면 안 되는데, 그것이 깨져도 값은 맞게 나와 드러나지 않는다 —
 * 8,504종목이면 그대로 8,504콜이다.
 */
class AlpacaSecurityMasterAdapterTest {

    private MockWebServer server;
    private SecurityNameCache nameCache;
    private AlpacaSecurityMasterAdapter adapter;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        nameCache = new SecurityNameCache();
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getAlpaca();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey("test-key-id");
        source.setSecretKey("test-secret");
        adapter = new AlpacaSecurityMasterAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("alpaca", 0, 0), nameCache,
                server.url("/").toString());
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("IMPORTANT — 캐시가 차 있으면 호출이 0건이다. 유니버스 응답이 이미 이름을 줬다")
    void servesFromCacheWithoutCalling() throws Exception {
        nameCache.put(new SecurityDescriptor(
                "US", "AAPL", "Apple Inc. Common Stock", null, null, null, null, "NASDAQ"));

        Optional<SecurityDescriptor> found = adapter.findByTicker("US", "AAPL");

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Apple Inc. Common Stock");
        assertThat(found.get().listingExchange()).isEqualTo("NASDAQ");
        assertThat(server.getRequestCount()).as("추가 호출이 없어야 한다").isZero();
    }

    @Test
    @DisplayName("캐시에 없으면 단건 조회로 내려간다 — 손으로 티커를 준 경로다")
    void fallsBackToSingleLookup() throws Exception {
        enqueue(200, fixture("asset-single.json"));

        Optional<SecurityDescriptor> found = adapter.findByTicker("US", "MSFT");

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Microsoft Corporation Common Stock");
        assertThat(found.get().listingExchange()).isEqualTo("NASDAQ");
        assertThat(server.takeRequest().getPath()).isEqualTo("/v2/assets/MSFT");
    }

    @Test
    @DisplayName("404는 예외가 아니라 빈 결과다 — 포트 계약이 「소스가 모른다」로 정의한다")
    void mapsNotFoundToEmpty() throws Exception {
        enqueue(404, "{\"message\":\"asset not found\"}");

        assertThat(adapter.findByTicker("US", "NOSUCH")).isEmpty();
    }

    @Test
    @DisplayName("401은 AUTH로 던진다 — 빈 결과로 접으면 이름이 티커로 굳는다")
    void mapsUnauthorizedToAuth() {
        enqueue(401, fixture("error-401.json"));

        assertThatThrownBy(() -> adapter.findByTicker("US", "AAPL"))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.AUTH);
    }

    @Test
    @DisplayName("담당 시장은 US 하나다 — 거래소는 listing_exchange가 가른다")
    void supportsUsMarketOnly() {
        assertThat(adapter.supportedMarketCodes()).containsExactly("US");
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body));
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/alpaca/" + name)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture를 읽을 수 없다: " + name, e);
        }
    }
}
