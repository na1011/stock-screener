package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.CompanyProfile;
import com.stockscreener.pipeline.datasource.DataSourceException;
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

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기업개황 파싱. 값은 {@code docs/api-spec/dart-company-profile.md}가 든 실측 응답이다.
 *
 * <p>이 어댑터에서만 갈리는 것이 둘이다 — <b>응답이 평면 객체</b>이고
 * <b>업종코드 자릿수가 3·5로 섞인다.</b> 둘 다 틀려도 예외가 나지 않고 값이 없거나
 * 조용히 다른 업종으로 묶이므로, 여기서 잡지 못하면 350콜을 태운 뒤에야 드러난다.
 */
class DartCompanyProfileAdapterTest {

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
    @DisplayName("평면 객체 응답에서 업종·결산월을 꺼낸다 — list 배열이 아니다")
    void parsesFlatObject() throws Exception {
        // IMPORTANT — 다른 DART 엔드포인트와 달리 `list`가 없다. requireList를 쓰면
        // 예외가 아니라 **빈 결과**가 되고, 그것은 "업종이 없는 종목"으로 읽힌다.
        respondWith("""
                {"status":"000","message":"정상","corp_name":"삼성전자(주)","stock_code":"005930",
                 "corp_cls":"Y","induty_code":"264","est_dt":"19690113","acc_mt":"12"}
                """);

        Optional<CompanyProfile> profile = newAdapter().fetchProfile(SAMSUNG);

        assertThat(profile).isPresent();
        assertThat(profile.get().industryCode()).isEqualTo("264");
        assertThat(profile.get().accountingMonth()).isEqualTo(12);
        assertThat(profile.get().corpClass()).isEqualTo("Y");
    }

    @Test
    @DisplayName("5자리 업종코드를 자르지 않고 원문 그대로 낸다")
    void keepsFiveDigitCodeVerbatim() throws Exception {
        // 소분류·중분류 키는 저장 계층의 생성 컬럼이 만든다(ADR-0044). 어댑터가 미리 자르면
        // 파생이 두 곳에서 일어나고, 어긋나도 아무 에러가 나지 않는다.
        respondWith("""
                {"status":"000","corp_name":"KB금융지주","stock_code":"105560",
                 "corp_cls":"Y","induty_code":"64992","acc_mt":"12"}
                """);

        Optional<CompanyProfile> profile =
                newAdapter().fetchProfile(new SecurityRef(2L, "KOSPI", "105560"));

        assertThat(profile).isPresent();
        assertThat(profile.get().industryCode())
                .as("앞 2자리(64)나 3자리(649)로 잘라 넘기면 원문이 사라진다")
                .isEqualTo("64992");
    }

    @Test
    @DisplayName("업종코드가 비어 오면 빈 결과다 — 종목 실패가 아니다")
    void treatsBlankIndustryCodeAsAbsent() throws Exception {
        respondWith("""
                {"status":"000","corp_name":"어떤회사","stock_code":"000000",
                 "corp_cls":"K","induty_code":"","acc_mt":"12"}
                """);

        assertThat(newAdapter().fetchProfile(SAMSUNG)).isEmpty();
    }

    @Test
    @DisplayName("데이터 없음(013)은 빈 결과다 — 매 실행마다 실패로 쌓이면 실제 장애를 덮는다")
    void treatsNoDataAsAbsent() throws Exception {
        respondWith("{\"status\":\"013\",\"message\":\"조회된 데이터가 없습니다.\"}");

        assertThat(newAdapter().fetchProfile(SAMSUNG)).isEmpty();
    }

    @Test
    @DisplayName("DART가 모르는 종목코드는 호출조차 하지 않고 빈 결과다")
    void skipsUnknownTicker() throws Exception {
        DartCompanyProfileAdapter adapter = newAdapter(new StubCorpCodeCache(Optional.empty()));

        assertThat(adapter.fetchProfile(new SecurityRef(3L, "KOSPI", "999999"))).isEmpty();
        assertThat(server.getRequestCount())
                .as("모르는 종목에 쿼터를 태우면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("잘못된 키는 AUTH다 — DART는 인증 실패도 HTTP 200으로 준다")
    void mapsInvalidKeyToAuth() {
        respondWith("{\"status\":\"010\",\"message\":\"등록되지 않은 키입니다.\"}");
        DartCompanyProfileAdapter adapter = newAdapter();

        assertThatThrownBy(() -> adapter.fetchProfile(SAMSUNG))
                .isInstanceOf(DataSourceException.class)
                .satisfies(e -> assertThat(((DataSourceException) e).kind())
                        .isEqualTo(DataSourceException.Kind.AUTH));
    }

    @Test
    @DisplayName("읽을 수 없는 결산월은 null이고 업종은 그대로 산다")
    void survivesUnreadableAccountingMonth() throws Exception {
        // 표본 6개사가 전부 "12"였으므로 다른 표기는 미확인이다. 결산월 하나 때문에
        // 업종까지 버리면 P8이 그 종목에서 통째로 무너진다.
        respondWith("""
                {"status":"000","corp_name":"어떤회사","stock_code":"111111",
                 "corp_cls":"Y","induty_code":"264","acc_mt":"3월"}
                """);

        Optional<CompanyProfile> profile = newAdapter().fetchProfile(SAMSUNG);

        assertThat(profile).isPresent();
        assertThat(profile.get().industryCode()).isEqualTo("264");
        assertThat(profile.get().accountingMonth()).isNull();
    }

    private void respondWith(String body) {
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json;charset=UTF-8")
                        .setBody(body);
            }
        });
    }

    private DartCompanyProfileAdapter newAdapter() {
        return newAdapter(new StubCorpCodeCache(Optional.of("00126380")));
    }

    private DartCompanyProfileAdapter newAdapter(DartCorpCodeCache corpCodes) {
        DataSourceProperties properties = new DataSourceProperties();
        DataSourceProperties.Source source = properties.getDart();
        source.setBaseUrl(server.url("/").toString());
        source.setApiKey("test-key");
        return new DartCompanyProfileAdapter(
                new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("dart", 0, 0), corpCodes);
    }

    /** corp_code 조회를 고정한다. 이 테스트의 관심사는 개황 파싱이다. */
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
