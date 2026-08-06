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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 응답을 녹화한 fixture로 재무 파싱과 Q4 유도를 검증한다. API 키가 필요 없다.
 *
 * <p>Q4 유도는 실측값으로 검증한다 — 삼성전자 2025 기준 FY 333.6조 − 3Q누적 239.8조 = 93.8조.
 */
class DartFinancialAdapterTest {

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
    @DisplayName("연간 보고서를 파싱한다 — 6개 계정이 모두 채워진다")
    void parsesAnnualReport() throws Exception {
        respondByReportCode();
        DartFinancialAdapter adapter = newAdapter();

        List<RawFinancialReport> reports = adapter.fetchReports(SAMSUNG, 2025);
        RawFinancialReport annual = findReport(reports, 2025, "FY");

        assertThat(annual.revenue()).isEqualByComparingTo("333605938000000");
        assertThat(annual.operatingIncome()).isEqualByComparingTo("43601051000000");
        assertThat(annual.netIncome()).isEqualByComparingTo("45206805000000");
        assertThat(annual.totalAssets()).isEqualByComparingTo("566942110000000");
        assertThat(annual.totalLiabilities()).isEqualByComparingTo("130621773000000");
        assertThat(annual.totalEquity()).isEqualByComparingTo("436320337000000");
        assertThat(annual.currencyCode()).isEqualTo("KRW");
    }

    @Test
    @DisplayName("Q4를 FY − 3Q누적으로 유도한다 — 4분기 보고서는 존재하지 않는다")
    void derivesFourthQuarter() throws Exception {
        respondByReportCode();
        DartFinancialAdapter adapter = newAdapter();

        List<RawFinancialReport> reports = adapter.fetchReports(SAMSUNG, 2025);
        RawFinancialReport q4 = findReport(reports, 2025, "Q4");

        // 실측: 333,605,938 - 239,768,567 = 93,837,371 (백만원 단위 생략)
        assertThat(q4.revenue()).isEqualByComparingTo("93837371000000");
        assertThat(q4.revenue().signum()).as("Q4 매출이 음수면 유도가 틀린 것이다").isPositive();
        assertThat(q4.revenue()).isLessThan(new java.math.BigDecimal("333605938000000"));
    }

    @Test
    @DisplayName("재무상태표는 Q4에서 차감하지 않는다 — 시점 잔액이라 빼면 음수가 된다")
    void doesNotSubtractBalanceSheetForQ4() throws Exception {
        respondByReportCode();
        DartFinancialAdapter adapter = newAdapter();

        List<RawFinancialReport> reports = adapter.fetchReports(SAMSUNG, 2025);
        RawFinancialReport q4 = findReport(reports, 2025, "Q4");
        RawFinancialReport annual = findReport(reports, 2025, "FY");

        // 연말 잔액이 곧 Q4 말 잔액이다.
        assertThat(q4.totalAssets()).isEqualByComparingTo(annual.totalAssets());
        assertThat(q4.totalEquity()).isEqualByComparingTo(annual.totalEquity());
        assertThat(q4.totalAssets().signum()).isPositive();
    }

    @Test
    @DisplayName("rcept_no 앞 8자리에서 공시일을 얻는다 — 정정공시 판별의 근거다")
    void derivesPublishedAtFromRceptNo() throws Exception {
        respondByReportCode();
        DartFinancialAdapter adapter = newAdapter();

        RawFinancialReport annual =
                findReport(adapter.fetchReports(SAMSUNG, 2025), 2025, "FY");

        assertThat(annual.publishedAt()).isNotNull();
        assertThat(annual.publishedAt().getYear()).isGreaterThanOrEqualTo(2025);
    }

    @Test
    @DisplayName("데이터 없음(013)은 예외가 아니다 — 아직 공시되지 않은 분기가 정상이다")
    void treatsNoDataAsSkippable() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                return json(fixture("error-no-data.json"));
            }
        });
        DartFinancialAdapter adapter = newAdapter();

        // 전 기간이 013이면 보고서가 하나도 없지만 예외는 아니다.
        assertThat(adapter.fetchReports(SAMSUNG, 2025)).isEmpty();
    }

    @Test
    @DisplayName("잘못된 키는 AUTH다 — DART는 인증 실패도 HTTP 200으로 준다")
    void mapsInvalidKeyToAuth() {
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                // IMPORTANT — 상태코드는 200이다. status를 파싱하지 않으면 정상으로 오인한다.
                return json(fixture("error-invalid-key.json"));
            }
        });
        DartFinancialAdapter adapter = newAdapter();

        assertThatThrownBy(() -> adapter.fetchReports(SAMSUNG, 2025))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> {
                    DataSourceException ex = (DataSourceException) e;
                    assertThat(ex.kind()).isEqualTo(DataSourceException.Kind.AUTH);
                    assertThat(ex.fatal()).isTrue();
                });
    }

    @Test
    @DisplayName("요청 제한 초과(020)는 RATE_LIMITED다")
    void mapsQuotaExceededToRateLimited() {
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                return json("{\"status\":\"020\",\"message\":\"요청 제한을 초과하였습니다.\"}");
            }
        });
        DartFinancialAdapter adapter = newAdapter();

        assertThatThrownBy(() -> adapter.fetchReports(SAMSUNG, 2025))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).retryable()).isTrue());
    }

    @Test
    @DisplayName("DART가 모르는 종목코드는 NOT_FOUND다")
    void mapsUnknownTickerToNotFound() {
        DartFinancialAdapter adapter = newAdapter(new StubCorpCodeCache(Optional.empty()));

        assertThatThrownBy(() -> adapter.fetchReports(
                new SecurityRef(2L, "KOSPI", "999999"), 2025))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).kind())
                        .isEqualTo(DataSourceException.Kind.NOT_FOUND));
    }

    @Test
    @DisplayName("비율 필드를 만들지 않는다 — 외부 계산값 의존이 타입 수준에서 불가능하다")
    void neverProducesRatioFields() throws Exception {
        respondByReportCode();
        DartFinancialAdapter adapter = newAdapter();

        RawFinancialReport annual =
                findReport(adapter.fetchReports(SAMSUNG, 2025), 2025, "FY");

        // RawFinancialReport에 PER·ROE 같은 필드가 아예 없다는 것이 구조적 보장이다.
        // 여기서는 원자료만 채워졌음을 확인한다.
        assertThat(annual.revenue()).isNotNull();
        assertThat(annual.eps()).as("주요계정에는 EPS가 없다 — 지표 계층이 계산한다").isNull();
        assertThat(annual.bps()).isNull();
    }

    /** reprt_code에 따라 연간·3분기 fixture를 나눠 준다. 나머지 기간은 데이터 없음. */
    private void respondByReportCode() {
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.contains("reprt_code=11011")) {
                    return json(fixture("annual-2025.json"));
                }
                if (path.contains("reprt_code=11014")) {
                    return json(fixture("q3-2025.json"));
                }
                return json(fixture("error-no-data.json"));
            }
        });
    }

    private DartFinancialAdapter newAdapter() {
        return newAdapter(new StubCorpCodeCache(Optional.of("00126380")));
    }

    private DartFinancialAdapter newAdapter(DartCorpCodeCache corpCodes) {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getDart();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey("test-key");
        return new DartFinancialAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("dart", 0, 0), corpCodes);
    }

    private static RawFinancialReport findReport(List<RawFinancialReport> reports,
                                                 int year, String period) {
        return reports.stream()
                .filter(r -> r.fiscalYear() == year && period.equals(r.fiscalPeriod()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        year + "/" + period + " 보고서가 없다. 받은 것: " + reports.stream()
                                .map(r -> r.fiscalYear() + "/" + r.fiscalPeriod()).toList()));
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body);
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/dart/" + name)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture를 읽을 수 없다: " + name, e);
        }
    }

    /** corp_code 조회를 고정한다. 이 테스트의 관심사는 재무 파싱이다. */
    private static final class StubCorpCodeCache extends DartCorpCodeCache {
        private final Optional<String> corpCode;

        StubCorpCodeCache(Optional<String> corpCode) {
            super(new ExternalApiClientFactory(), new DataSourceProperties(),
                    new TokenBucketRateLimiter("dart", 0, 0));
            this.corpCode = corpCode;
        }

        @Override
        public Optional<String> findCorpCode(String stockCode) {
            return corpCode;
        }
    }
}
