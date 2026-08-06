package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 응답을 녹화한 fixture로 파싱과 에러 매핑을 검증한다. API 키가 필요 없다.
 *
 * <p>MockWebServer를 쓰는 이유는 실제 소켓을 열어 {@code RestClient}를 통과시키기 위해서다.
 * {@code RestClient}를 목킹하면 봉투 판별·상태코드 처리가 전혀 검증되지 않는다.
 */
class DataGoKrPriceAdapterTest {

    private static final SecurityRef SAMSUNG = new SecurityRef(1L, "KOSPI", "005930");

    private MockWebServer server;
    private DataGoKrPriceAdapter adapter;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = newAdapter("test-key");
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("정상 응답을 봉으로 파싱하고 날짜 오름차순으로 낸다")
    void parsesBars() throws Exception {
        enqueue(200, fixture("price-ok.json"));

        List<RawPriceBar> bars = adapter.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 6));

        assertThat(bars).hasSize(3);
        assertThat(bars).extracting(RawPriceBar::tradeDate).isSorted();

        RawPriceBar latest = bars.get(bars.size() - 1);
        assertThat(latest.tradeDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(latest.close()).isEqualByComparingTo("246000");
        assertThat(latest.open()).isEqualByComparingTo("254000");
        assertThat(latest.high()).isEqualByComparingTo("254500");
        assertThat(latest.low()).isEqualByComparingTo("244000");
        assertThat(latest.volume()).isEqualTo(22577128L);
        // KRX는 거래대금을 실제로 제공한다 — close × volume 합성이 아니다.
        assertThat(latest.tradeValue()).isEqualByComparingTo("5610076087593");
        // 원주가를 준다. 수정주가는 우리가 만든다.
        assertThat(latest.splitAdjustedBySource()).isFalse();
        assertThat(latest.isValid()).isTrue();
    }

    @Test
    @DisplayName("종목별 기간조회 파라미터를 보낸다 — 이것이 초기 적재를 350콜로 만든다")
    void sendsDateRangeQuery() throws Exception {
        enqueue(200, fixture("price-ok.json"));

        adapter.fetchDailyBars(SAMSUNG, LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 5));

        RecordedRequest request = server.takeRequest();
        String query = URLDecoder.decode(request.getPath(), StandardCharsets.UTF_8);
        assertThat(query).contains("likeSrtnCd=005930");
        assertThat(query).contains("beginBasDt=20260727");
        // endBasDt는 "미만"이라 to를 포함하려면 하루 더해야 한다.
        assertThat(query).contains("endBasDt=20260806");
        assertThat(query).contains("resultType=json");
    }

    @Test
    @DisplayName("빈 결과는 예외가 아니다 — 휴장·미상장이 정상적으로 발생한다")
    void emptyResultIsNotAnError() throws Exception {
        enqueue(200, fixture("price-empty.json"));

        assertThat(adapter.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1))).isEmpty();
    }

    @Test
    @DisplayName("미등록 키는 AUTH다 — 봉투가 정상 응답과 완전히 다르다")
    void mapsUnregisteredKeyToAuth() {
        // 실측: HTTP 403 + OpenAPI_ServiceResponse.cmmMsgHeader (response.header가 아니다)
        enqueue(403, fixture("error-unregistered-key.json"));

        assertThatThrownBy(() -> adapter.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5)))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> {
                    DataSourceException ex = (DataSourceException) e;
                    assertThat(ex.kind()).isEqualTo(DataSourceException.Kind.AUTH);
                    assertThat(ex.fatal())
                            .as("키가 틀렸는데 850종목을 시도할 이유가 없다")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("요청 제한 초과(22)는 RATE_LIMITED다 — 재시도 가치가 있다")
    void mapsQuotaExceededToRateLimited() {
        enqueue(200, """
                {"response":{"header":{"resultCode":"22",
                 "resultMsg":"LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"},"body":{}}}""");

        assertThatThrownBy(() -> adapter.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5)))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).retryable()).isTrue());
    }

    @Test
    @DisplayName("알 수 없는 봉투는 MALFORMED다 — 스펙 변경 신호를 조용히 넘기지 않는다")
    void mapsUnknownEnvelopeToMalformed() {
        enqueue(200, "{\"somethingElse\":{}}");

        assertThatThrownBy(() -> adapter.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5)))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).kind())
                        .isEqualTo(DataSourceException.Kind.MALFORMED))
                .hasMessageContaining("somethingElse");
    }

    @Test
    @DisplayName("잘못된 티커는 호출 전에 막는다 — 빈 결과와 구별되지 않기 때문이다")
    void validatesTickerBeforeCalling() {
        SecurityRef bad = new SecurityRef(1L, "KOSPI", "5930");

        assertThatThrownBy(() -> adapter.fetchDailyBars(
                bad, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5)))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("6자리");

        assertThat(server.getRequestCount())
                .as("검증에 걸린 요청은 아예 나가지 않아야 한다 — 쿼터를 태울 이유가 없다")
                .isZero();
    }

    @Test
    @DisplayName("구간이 뒤집히면 호출 전에 막는다")
    void validatesDateRange() {
        assertThatThrownBy(() -> adapter.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("뒤집");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("키가 없으면 AUTH로 실패한다 — 기동은 되어야 하므로 예외는 조회 시점이다")
    void failsWithAuthWhenKeyMissing() {
        DataGoKrPriceAdapter keyless = newAdapter("");

        assertThatThrownBy(() -> keyless.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5)))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("DATA_GO_KR_SERVICE_KEY");
    }

    @Test
    @DisplayName("다른 종목이 섞여 오면 걸러낸다 — likeSrtnCd는 부분 일치다")
    void filtersOtherTickers() throws Exception {
        enqueue(200, """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                 "body":{"numOfRows":2,"pageNo":1,"totalCount":2,"items":{"item":[
                  {"basDt":"20260805","srtnCd":"005930","itmsNm":"삼성전자","mrktCtg":"KOSPI",
                   "clpr":"246000","mkp":"254000","hipr":"254500","lopr":"244000",
                   "trqu":"100","trPrc":"200"},
                  {"basDt":"20260805","srtnCd":"0059301","itmsNm":"다른종목","mrktCtg":"KOSPI",
                   "clpr":"1000","mkp":"1000","hipr":"1000","lopr":"1000",
                   "trqu":"10","trPrc":"20"}]}}}}""");

        List<RawPriceBar> bars = adapter.fetchDailyBars(
                SAMSUNG, LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 5));

        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).close()).isEqualByComparingTo("246000");
    }

    private DataGoKrPriceAdapter newAdapter(String apiKey) {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getDataGoKr();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey(apiKey);
        return new DataGoKrPriceAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("data-go-kr", 0, 0));
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body));
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("fixtures/datagokr/" + name)
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture를 읽을 수 없다: " + name, e);
        }
    }
}
