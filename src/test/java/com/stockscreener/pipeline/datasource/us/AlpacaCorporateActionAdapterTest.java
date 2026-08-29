package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceException;
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
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 미국 기업 행위 어댑터의 비율 방향·창 필터·봉투 판별을 검증한다(ADR-0090).
 *
 * <p>MockWebServer를 쓰는 이유는 {@code AlpacaPriceAdapterTest}와 같다 — {@code RestClient}를
 * 목킹하면 봉투 판별과 상태코드 처리가 전혀 검증되지 않는다.
 *
 * <p>픽스처는 {@code AlpacaCorporateActionProbeLiveTest}가 실제로 받은 응답을 줄인 것이다.
 * <b>가장 중요한 단언 둘</b>은 ① 분할과 병합의 부호가 왕복에서 반대인가와 ② 요청 창
 * ({@code process_date})과 필터 창({@code ex_date})이 다른 것을 어댑터가 아는가다.
 * 하나만 재면 항등식이다(ADR-0049).
 */
class AlpacaCorporateActionAdapterTest {

    private static final SecurityRef NVDA = new SecurityRef(1L, "US", "NVDA");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 12, 31);

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
    @DisplayName("분할을 옮긴다 — ratio는 new_rate/old_rate이고 날짜 셋을 갈라 싣는다")
    void mapsForwardSplit() throws Exception {
        enqueue(200, fixture("ca-ok.json"));

        List<CorporateAction> actions = adapter().fetchCorporateActions(NVDA, FROM, TO);

        assertThat(actions).hasSize(1);
        CorporateAction split = actions.get(0);
        assertThat(split.type()).isEqualTo(CorporateAction.Type.SPLIT);
        assertThat(split.ratio()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(split.effectiveDate()).isEqualTo(LocalDate.of(2024, 6, 10));
        assertThat(split.listingDate()).as("분할은 권리락과 신주상장이 같은 날이다(실측)")
                .isEqualTo(LocalDate.of(2024, 6, 10));
        assertThat(split.sourceDate())
                .as("소스 원값을 그대로 싣는다 — 우리가 일자를 보정하지 않았다는 기록이다")
                .isEqualTo(LocalDate.of(2024, 6, 10));
        assertThat(split.source()).isEqualTo(CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
        assertThat(split.sourceForm()).isEqualTo("forward_split");
        assertThat(split.sourceForm().length())
                .as("corporate_action.source_form이 varchar(32)다").isLessThanOrEqualTo(32);
    }

    @Test
    @DisplayName("병합의 부호가 분할과 반대다 — 한쪽만 재는 테스트는 항등식이다")
    void mapsReverseSplitWithTheOppositeSign() throws Exception {
        enqueue(200, fixture("ca-reverse.json"));

        List<CorporateAction> actions = adapter().fetchCorporateActions(NVDA, FROM, TO);

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type()).isEqualTo(CorporateAction.Type.REVERSE_SPLIT);
        assertThat(actions.get(0).ratio())
                .as("25:1 병합이면 1주가 0.04주가 된다")
                .isEqualByComparingTo(new BigDecimal("0.04"));
    }

    @Test
    @DisplayName("3:1 병합에서 나눗셈이 죽지 않는다 — 스케일이 없으면 배치 전체가 죽는다")
    void dividesWithAScaleSoAThirdDoesNotThrow() throws Exception {
        enqueue(200, fixture("ca-reverse-thirds.json"));

        List<CorporateAction> actions = adapter().fetchCorporateActions(NVDA, FROM, TO);

        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).ratio())
                .as("1/3은 유한소수가 아니다. 스케일 없는 divide는 ArithmeticException이고 "
                        + "그것은 종목 실패가 아니라 배치 전체의 죽음이다")
                .isEqualByComparingTo(new BigDecimal("0.3333333333"));
    }

    @Test
    @DisplayName("배당·분사는 값으로 내지 않는다 — 담을 자리가 없는 것이지 사건이 없는 것이 아니다")
    void doesNotRecordDividendsOrSpinOffs() throws Exception {
        enqueue(200, fixture("ca-ok.json"));

        List<CorporateAction> actions = adapter().fetchCorporateActions(NVDA, FROM, TO);

        assertThat(actions)
                .as("같은 응답에 배당 1건·분사 1건이 함께 있는 픽스처다")
                .extracting(CorporateAction::sourceForm)
                .containsExactly("forward_split");
    }

    @Test
    @DisplayName("ex_date로 거른다 — process_date가 창 안이어도 효력일이 밖이면 안 담는다")
    void filtersByExDateNotProcessDate() throws Exception {
        enqueue(200, fixture("ca-out-of-window.json"));

        List<CorporateAction> actions = adapter().fetchCorporateActions(NVDA, FROM, TO);

        assertThat(actions)
                .as("ex_date 2022-04-11 / process_date 2024-06-10인 사건이다. "
                        + "포트는 effectiveDate 기준 필터를 요구한다")
                .isEmpty();
    }

    @Test
    @DisplayName("요청 창을 필터 창보다 넓게 보낸다 — 소스가 process_date로 거르기 때문이다")
    void requestsAWiderProcessDateWindow() throws Exception {
        enqueue(200, fixture("ca-empty.json"));

        adapter().fetchCorporateActions(NVDA, FROM, TO);

        String query = query(server.takeRequest());
        assertThat(query)
                .as("효력일이 창 안인데 그보다 앞서 처리된 사건을 놓치지 않으려는 것이다")
                .contains("start=2023-01-01");
        assertThat(query).contains("symbols=NVDA")
                .contains("data_quality=complete")
                .contains("region=us")
                .contains("limit=1000")
                .contains("sort=asc");
        assertThat(query)
                .as("types로 좁히면 담지 않기로 한 종류의 건수가 안 오고 새 타입 경보도 죽는다")
                .doesNotContain("types=");
    }

    @Test
    @DisplayName("페이지를 끝까지 돈다 — 정렬이 심볼 우선이라 중간에 멈추면 뒤가 통째로 빈다")
    void followsPagesToTheEnd() throws Exception {
        enqueue(200, fixture("ca-page1.json"));
        enqueue(200, fixture("ca-page2.json"));

        List<CorporateAction> actions = adapter().fetchCorporateActions(NVDA, FROM, TO);

        assertThat(actions).hasSize(2);
        assertThat(actions).extracting(CorporateAction::effectiveDate).isSorted();
        assertThat(actions).extracting(CorporateAction::type)
                .containsExactly(CorporateAction.Type.SPLIT, CorporateAction.Type.REVERSE_SPLIT);
    }

    @Test
    @DisplayName("페이지 상한을 넘으면 부분 결과를 버린다 — 잘린 목록은 0건과 구별되지 않는다")
    void refusesPartialResultsBeyondThePageLimit() {
        enqueue(200, fixture("ca-page1.json"));
        enqueue(200, fixture("ca-page1.json"));

        assertThatThrownBy(() -> adapter(1).fetchCorporateActions(NVDA, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("부분 결과");
    }

    @Test
    @DisplayName("빈 봉투는 실패가 아니다 — 그 창에 사건이 없다는 뜻이다")
    void emptyEnvelopeIsNotAFailure() throws Exception {
        enqueue(200, fixture("ca-empty.json"));

        assertThat(adapter().fetchCorporateActions(NVDA, FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("corporate_actions 키가 없으면 0건이 아니라 스펙 변경이다")
    void missingEnvelopeKeyIsMalformed() {
        enqueue(200, "{\"next_page_token\":null}");

        assertThatThrownBy(() -> adapter().fetchCorporateActions(NVDA, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("403은 AUTH다 — MALFORMED로 낮추면 종목마다 조용히 실패하고 부분 성공으로 끝난다")
    void mapsForbiddenToAuth() {
        enqueue(403, "{\"message\":\"forbidden\"}");

        assertThatThrownBy(() -> adapter().fetchCorporateActions(NVDA, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .as("AUTH는 fatal()이라 배치 전체를 세운다. 그것이 의도다")
                .isEqualTo(DataSourceException.Kind.AUTH);
    }

    @Test
    @DisplayName("401 본문이 HTML이어도 AUTH다 — JsonNode로 받으면 상태코드를 보기 전에 터져 TRANSIENT가 된다")
    void mapsUnauthorizedToAuthEvenWhenBodyIsHtml() {
        // 실측(2026-08-29): 이 엔드포인트의 401 본문은 JSON이 아니라 nginx HTML이고
        // Content-Type이 text/html이다. 본문을 JsonNode로 받으면 변환이 먼저 터지고,
        // 그 RuntimeException이 어댑터의 일반 catch에 걸려 인증 실패가 TRANSIENT가 된다 —
        // 키가 틀린 것이 원인인데 종목마다 「일시 오류」로 기록되고 배치는 부분 성공으로 끝난다.
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>\n<head><title>401 Authorization Required</title></head>\n"
                        + "<body><center><h1>401 Authorization Required</h1></center></body>\n</html>"));

        assertThatThrownBy(() -> adapter().fetchCorporateActions(NVDA, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .as("TRANSIENT면 원인이 우리 키라는 사실이 종목별 실패에 묻힌다")
                .isEqualTo(DataSourceException.Kind.AUTH);
    }

    @Test
    @DisplayName("200인데 JSON이 아니면 MALFORMED다 — 재시도해도 안 되는 것을 재시도로 적지 않는다")
    void mapsNonJsonSuccessBodyToMalformed() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>maintenance</body></html>"));

        assertThatThrownBy(() -> adapter().fetchCorporateActions(NVDA, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind())
                .isEqualTo(DataSourceException.Kind.MALFORMED);
    }

    @Test
    @DisplayName("다른 심볼의 사건은 붙이지 않는다 — 붙이면 엉뚱한 종목의 계수가 틀린다")
    void skipsRowsOfAnotherSymbol() throws Exception {
        enqueue(200, fixture("ca-other-symbol.json"));

        assertThat(adapter().fetchCorporateActions(NVDA, FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("소스의 낱말과 비율의 부호가 어긋나면 지어내지 않고 종목 실패로 남긴다")
    void rejectsASignMismatch() {
        enqueue(200, fixture("ca-sign-mismatch.json"));

        assertThatThrownBy(() -> adapter().fetchCorporateActions(NVDA, FROM, TO))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("부호가 어긋난다");
    }

    @Test
    @DisplayName("처음 보는 타입이 와도 값은 안 만들고 조용히 넘기지도 않는다")
    void doesNotRecordUnknownTypes() throws Exception {
        enqueue(200, fixture("ca-unknown-type.json"));

        assertThat(adapter().fetchCorporateActions(NVDA, FROM, TO))
                .as("16종 밖의 타입은 값으로 안 낸다. 어댑터가 WARN으로 알린다")
                .isEmpty();
    }

    @Test
    @DisplayName("구간이 뒤집히면 부르기 전에 막는다 — 빈 결과가 「데이터 없음」과 같아진다")
    void rejectsAnInvertedWindow() {
        assertThatThrownBy(() -> adapter().fetchCorporateActions(NVDA, TO, FROM))
                .isInstanceOf(DataSourceException.class)
                .hasMessageContaining("뒤집혔");
        assertThat(server.getRequestCount()).as("콜을 태우지 않는다").isZero();
    }

    private static String query(RecordedRequest request) {
        String path = request.getPath() == null ? "" : request.getPath();
        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    private AlpacaCorporateActionAdapter adapter() {
        return adapter(50);
    }

    private AlpacaCorporateActionAdapter adapter(int maxPages) {
        return new AlpacaCorporateActionAdapter(
                new ExternalApiClientFactory(), properties(),
                new TokenBucketRateLimiter("alpaca", 0, 0), server.url("/").toString(), maxPages);
    }

    private static DataSourceProperties properties() {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getAlpaca().setApiKey("test-key-id");
        properties.getAlpaca().setSecretKey("test-secret");
        return properties;
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
