package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DartResponse#read}의 계약 — HTTP 상태를 먼저 보고, 2xx 본문을 UTF-8로 판다(ADR-0174).
 *
 * <p>재무 어댑터로 몬다 — 호출부 다섯이 같은 판독을 쓰고, 어댑터의 「데이터 없음」 삼킴이 HTTP 실패를 삼키지 않는지까지 한
 * 자리에서 보인다.
 */
class DartResponseTest {

    private static final SecurityRef SAMSUNG = new SecurityRef(1L, "KOSPI", "005930");

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("HTML 429 · JSON 429 → RATE_LIMITED — 본문 모양과 무관하게 상태가 먼저다")
    void tooManyRequestsIsRateLimited() {
        assertThat(kindOf(respond(429, "text/html", "<html>slow down</html>")))
                .isEqualTo(DataSourceException.Kind.RATE_LIMITED);
        assertThat(kindOf(respond(429, "application/json", "{\"status\":\"020\",\"message\":\"한도\"}")))
                .isEqualTo(DataSourceException.Kind.RATE_LIMITED);
    }

    @Test
    @DisplayName("빈 502 · 200 HTML → TRANSIENT — 받기 실패이지 「응답 해석 실패」가 아니다")
    void gatewayAndErrorPageAreTransient() {
        assertThat(kindOf(respond(502, null, ""))).isEqualTo(DataSourceException.Kind.TRANSIENT);
        assertThat(kindOf(respond(200, "text/html", "<html><body>점검 중</body></html>")))
                .isEqualTo(DataSourceException.Kind.TRANSIENT);
        assertThat(kindOf(respond(200, "application/json", ""))).as("200 빈 본문")
                .isEqualTo(DataSourceException.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("IMPORTANT — HTTP 404 HTML은 실패로 남는다 — NOT_FOUND로 매기면 어댑터가 「데이터 없음」으로 삼켜 빈 성공이 된다")
    void httpNotFoundIsNotSwallowed() {
        DataSourceException failure = failureOf(respond(404, "text/html", "<html>Not Found</html>"));
        assertThat(failure.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT);
        assertThat(failure.sourceAnswered()).isFalse();
    }

    @Test
    @DisplayName("JSON status 013만 「데이터 없음」으로 삼킨다 — 전 기간 013이면 빈 결과로 성공한다")
    void jsonNoDataIsSwallowed() throws Exception {
        server.setDispatcher(dispatcher(respond(200, "application/json", fixture("error-no-data.json"))));
        assertThat(adapter().fetchReports(SAMSUNG, 2025).reports()).isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 200 text/html(charset 없음) · content-type 없음에도 한글 계정이 안 깨진다 — 문자열로 받으면 ISO-8859-1로 깨진다")
    void koreanSurvivesNonJsonContentType() throws Exception {
        BigDecimal expected = annualRevenue(serveAnnualWith("application/json;charset=UTF-8"));
        assertThat(expected).as("대조 — JSON 타입이면 원래 읽힌다").isEqualByComparingTo("333605938000000");

        assertThat(annualRevenue(serveAnnualWith("text/html"))).isEqualByComparingTo(expected);
        assertThat(annualRevenue(serveAnnualWith(null))).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("021·100은 우리 요청 오류 — MALFORMED이고 「답함」 표지가 없다(답 못 받음)")
    void requestErrorsAreNotAnswers() {
        for (String status : List.of("021", "100")) {
            DataSourceException failure = failureOf(respond(200, "application/json",
                    "{\"status\":\"" + status + "\",\"message\":\"x\"}"));
            assertThat(failure.kind()).as(status).isEqualTo(DataSourceException.Kind.MALFORMED);
            assertThat(failure.sourceAnswered()).as(status).isFalse();
        }
    }

    @Test
    @DisplayName("신선한 매핑의 부재(재무)는 NOT_FOUND이고 「답함」 표지를 단다")
    void freshMappingAbsenceIsAnswered() {
        DartFinancialAdapter adapter = adapter(Optional.empty());
        assertThatThrownBy(() -> adapter.fetchReports(new SecurityRef(2L, "KOSPI", "999999"), 2025))
                .isInstanceOfSatisfying(DataSourceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DataSourceException.Kind.NOT_FOUND);
                    assertThat(e.sourceAnswered()).isTrue();
                });
    }

    private BigDecimal annualRevenue(Function<RecordedRequest, MockResponse> serve) throws Exception {
        server.setDispatcher(dispatcher(serve));
        return adapter().fetchReports(SAMSUNG, 2025).reports().stream()
                .filter(r -> r.fiscalYear() == 2025 && "FY".equals(r.fiscalPeriod()))
                .map(RawFinancialReport::revenue)
                .findFirst().orElseThrow();
    }

    /** 연간은 그 content-type으로, 나머지 기간은 013으로 낸다. */
    private static Function<RecordedRequest, MockResponse> serveAnnualWith(String contentType) {
        return request -> {
            String path = request.getPath() == null ? "" : request.getPath();
            String body = path.contains("reprt_code=11011") ? fixture("annual-2025.json") : fixture("error-no-data.json");
            return response(200, contentType, body);
        };
    }

    private Function<RecordedRequest, MockResponse> respond(int status, String contentType, String body) {
        return request -> response(status, contentType, body);
    }

    private DataSourceException.Kind kindOf(Function<RecordedRequest, MockResponse> serve) {
        return failureOf(serve).kind();
    }

    private DataSourceException failureOf(Function<RecordedRequest, MockResponse> serve) {
        server.setDispatcher(dispatcher(serve));
        try {
            adapter().fetchReports(SAMSUNG, 2025);
        } catch (DataSourceException e) {
            return e;
        }
        throw new AssertionError("실패가 나야 한다 — 빈 성공으로 끝났다");
    }

    private static Dispatcher dispatcher(Function<RecordedRequest, MockResponse> serve) {
        return new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                return serve.apply(request);
            }
        };
    }

    private static MockResponse response(int status, String contentType, String body) {
        // UTF-8 바이트로 싣는다 — DART가 실제로 내는 인코딩이다. content-type에 charset을 안 붙이는 것이 시험의 요점이다.
        okio.Buffer buffer = new okio.Buffer().write(body.getBytes(StandardCharsets.UTF_8));
        MockResponse response = new MockResponse().setResponseCode(status).setBody(buffer);
        return contentType == null ? response.removeHeader("Content-Type") : response.setHeader("Content-Type", contentType);
    }

    private DartFinancialAdapter adapter() {
        return adapter(Optional.of("00126380"));
    }

    private DartFinancialAdapter adapter(Optional<String> corpCode) {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getDart();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey("test-key");
        return new DartFinancialAdapter(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("dart", 0, 0), new StubCorpCodeCache(corpCode));
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/dart/" + name).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture를 읽을 수 없다: " + name, e);
        }
    }

    private static final class StubCorpCodeCache extends DartCorpCodeCache {
        private final Optional<String> corpCode;

        StubCorpCodeCache(Optional<String> corpCode) {
            super(new ExternalApiClientFactory(), new DataSourceProperties(), new TokenBucketRateLimiter("dart", 0, 0));
            this.corpCode = corpCode;
        }

        @Override
        public Optional<String> findCorpCode(String stockCode) {
            return corpCode;
        }
    }
}
