package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
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

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시총 상위 유니버스 선정 규칙을 검증한다(ADR-0012).
 *
 * <p>가장 중요한 것은 <b>보통주만 고른다</b>는 것이다. 우선주를 거르지 않으면 같은 회사가
 * 두 번 편입되어 스크리닝 결과에 중복이 생기고, 시총 상위 200에 실제로 5종목이 섞인다.
 */
class MarketCapUniverseAdapterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    /**
     * 상위 2종목만 고르는 테스트용 유니버스.
     *
     * <p>운영값(200)을 쓰면 선정 규칙 하나를 확인하는 데 픽스처에 200종목을 넣어야 하고,
     * 그 잡음이 정작 검증하려는 규칙을 덮는다. 규칙은 N과 무관하게 성립한다.
     */
    private static final Map<String, MarketCapUniverseAdapter.Target> SMALL_TARGETS =
            Map.of("KOSPI_TOP200", new MarketCapUniverseAdapter.Target("KOSPI", 2));

    private MockWebServer server;
    private MarketCapUniverseAdapter adapter;
    private SecurityNameCache nameCache;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        nameCache = new SecurityNameCache();
        adapter = newAdapter("test-key");
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("시가총액 내림차순 상위 N을 고른다")
    void picksTopNByMarketCap() throws Exception {
        enqueue(200, page(
                stock("000100", "삼백조", "KOSPI", "300000000000000"),
                stock("000200", "일백조", "KOSPI", "100000000000000"),
                stock("000300", "이백조", "KOSPI", "200000000000000")));

        UniverseSnapshot snapshot = fetchTop(2);

        assertThat(snapshot.tickers())
                .as("시총 큰 순서로 상위 2종목만 — 100조짜리는 잘린다")
                .containsExactly("000100", "000300");
        assertThat(snapshot.sourceName()).isEqualTo("data-go-kr:market-cap");
    }

    @Test
    @DisplayName("우선주를 제외한다 — 끝자리가 0이 아니면 보통주가 아니다")
    void excludesPreferredStock() throws Exception {
        enqueue(200, page(
                stock("005930", "삼성전자", "KOSPI", "300000000000000"),
                stock("005935", "삼성전자우", "KOSPI", "200000000000000"),
                stock("000660", "SK하이닉스", "KOSPI", "100000000000000")));

        UniverseSnapshot snapshot = fetchTop(2);

        assertThat(snapshot.tickers())
                .as("우선주가 들어가면 같은 회사가 두 번 편입된다")
                .containsExactly("005930", "000660");
    }

    @Test
    @DisplayName("스팩·리츠를 제외한다 — 사업회사가 아니라 재무 지표가 성립하지 않는다")
    void excludesSpacsAndReits() throws Exception {
        enqueue(200, page(
                stock("000100", "우량회사", "KOSPI", "300000000000000"),
                stock("000200", "케이비제12호스팩", "KOSPI", "250000000000000"),
                stock("000300", "대신밸류리츠", "KOSPI", "240000000000000"),
                stock("000400", "다른회사", "KOSPI", "100000000000000")));

        UniverseSnapshot snapshot = fetchTop(2);

        assertThat(snapshot.tickers()).containsExactly("000100", "000400");
    }

    @Test
    @DisplayName("다른 시장 종목을 제외한다")
    void excludesOtherMarkets() throws Exception {
        enqueue(200, page(
                stock("000100", "코스피회사", "KOSPI", "300000000000000"),
                stock("000200", "코스닥회사", "KOSDAQ", "250000000000000"),
                stock("000300", "코스피회사2", "KOSPI", "100000000000000")));

        UniverseSnapshot snapshot = fetchTop(2);

        assertThat(snapshot.tickers()).containsExactly("000100", "000300");
    }

    @Test
    @DisplayName("상위 N을 채우지 못하면 부분 목록을 내지 않고 실패한다")
    void failsInsteadOfReturningPartialList() {
        // ADR-0008 MUST — 리밸런싱이 "목록에 없으면 제외"로 동작하므로 부분 목록은 대량 오탈락이다.
        enqueue(200, page(stock("000100", "하나뿐", "KOSPI", "300000000000000")));

        assertThatThrownBy(() -> adapter.fetchConstituents("KOSPI_TOP200", AS_OF))
                .as("2종목을 요구하는데 1종목만 얻었다")
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("부분 목록을 반영하지 않는다");
    }

    @Test
    @DisplayName("휴장일이면 직전 거래일로 거슬러 올라간다")
    void fallsBackToPreviousTradingDay() throws Exception {
        enqueue(200, emptyPage());
        enqueue(200, page(
                stock("000100", "회사", "KOSPI", "300000000000000"),
                stock("000200", "회사2", "KOSPI", "100000000000000")));

        UniverseSnapshot snapshot = fetchTop(2);

        assertThat(snapshot.tickers()).hasSize(2);
        server.takeRequest();
        String second = URLDecoder.decode(server.takeRequest().getPath(), StandardCharsets.UTF_8);
        assertThat(second)
                .as("하루 전으로 거슬러 올라가야 한다")
                .contains("basDt=20260804");
    }

    @Test
    @DisplayName("종목명을 캐시에 넣는다 — 등록 시 마스터 API를 다시 부르지 않게 한다")
    void cachesSecurityNames() throws Exception {
        enqueue(200, page(
                stock("005930", "삼성전자", "KOSPI", "300000000000000"),
                stock("000660", "SK하이닉스", "KOSPI", "100000000000000")));

        fetchTop(2);

        assertThat(nameCache.find("KOSPI", "005930"))
                .get()
                .extracting(d -> d.name())
                .isEqualTo("삼성전자");
    }

    @Test
    @DisplayName("시장 필터 파라미터를 보낸다 — 전 시장을 받아 거르면 페이지가 두 배가 된다")
    void sendsMarketFilter() throws Exception {
        enqueue(200, page(
                stock("000100", "회사", "KOSPI", "300000000000000"),
                stock("000200", "회사2", "KOSPI", "100000000000000")));

        fetchTop(2);

        RecordedRequest request = server.takeRequest();
        String query = URLDecoder.decode(request.getPath(), StandardCharsets.UTF_8);
        assertThat(query).contains("mrktCls=KOSPI");
        assertThat(query).contains("basDt=20260805");
        // 종목코드를 주지 않아야 전종목이 온다.
        assertThat(query).doesNotContain("likeSrtnCd");
    }

    @Test
    @DisplayName("담당하지 않는 유니버스는 NOT_FOUND다")
    void rejectsUnknownUniverse() {
        assertThatThrownBy(() -> adapter.fetchConstituents("NASDAQ100", AS_OF))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("담당하지 않는 유니버스");
    }

    private UniverseSnapshot fetchTop(int ignored) throws DataSourceException {
        return adapter.fetchConstituents("KOSPI_TOP200", AS_OF);
    }

    private MarketCapUniverseAdapter newAdapter(String apiKey) {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getDataGoKr();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey(apiKey);
        return new MarketCapUniverseAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("data-go-kr", 0, 0), nameCache, SMALL_TARGETS);
    }

    private static String stock(String ticker, String name, String market, String marketCap) {
        return """
                {"basDt":"20260805","srtnCd":"%s","itmsNm":"%s","mrktCtg":"%s",
                 "clpr":"1000","mkp":"1000","hipr":"1000","lopr":"1000",
                 "trqu":"100","trPrc":"200","lstgStCnt":"1000","mrktTotAmt":"%s"}"""
                .formatted(ticker, name, market, marketCap);
    }

    private static String page(String... stocks) {
        List<String> items = new ArrayList<>(List.of(stocks));
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                 "body":{"numOfRows":2000,"pageNo":1,"totalCount":%d,"items":{"item":[%s]}}}}"""
                .formatted(items.size(), String.join(",", items));
    }

    private static String emptyPage() {
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                 "body":{"numOfRows":2000,"pageNo":1,"totalCount":0,"items":{"item":[]}}}}""";
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(body));
    }
}
