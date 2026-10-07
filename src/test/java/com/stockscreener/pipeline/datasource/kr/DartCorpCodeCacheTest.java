package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.batch.MarketCompleteness;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DartCorpCodeCache}의 계약 — 24시간 갱신 · 하한 · 낡은 판 · 즉시 1회 재시도와 10분 기억(ADR-0174).
 *
 * <p><b>지키는 것</b>: 낡은 판이나 반쯤 온 판의 「모른다」를 소스가 답한 부재로 읽지 않는다. 그렇게 읽으면 그 종목의 시장이
 * 끝난 것으로 판정되어 신선도 시계를 민다.
 */
class DartCorpCodeCacheTest {

    private MockWebServer server;
    private MutableClock clock;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        clock = new MutableClock(Instant.parse("2026-10-10T05:00:00Z"));
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("받은 지 하루가 지나면 다시 받는다 — 하루 안이면 안 부른다 · 신선한 판의 부재는 빈 값(소스의 답)")
    void refreshesAfterADay() throws Exception {
        enqueueZip(Map.of("005930", "00126380", "000660", "00164779"));
        enqueueZip(Map.of("005930", "99999999", "035420", "00266961"));
        DartCorpCodeCache cache = cache(2);

        assertThat(cache.findCorpCode("005930")).contains("00126380");
        assertThat(cache.findCorpCode("123456")).as("신선한 판의 부재").isEmpty();
        clock.advance(Duration.ofHours(23));
        assertThat(cache.findCorpCode("005930")).contains("00126380");
        assertThat(server.getRequestCount()).isEqualTo(1);
        clock.advance(Duration.ofHours(2));
        assertThat(cache.findCorpCode("005930")).as("하루가 지나면 새 판").contains("99999999");
        assertThat(cache.findCorpCode("035420")).as("새 판의 신규 상장").contains("00266961");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("IMPORTANT — 하한 미달(0건 ZIP)이면 옛 판을 낡은 판으로 지키고, 그 판에 없는 종목코드는 TRANSIENT — KOSPI 미완료의 재료")
    void underflowKeepsTheOldVersionAsStale() throws Exception {
        enqueueZip(Map.of("005930", "00126380", "000660", "00164779"));
        enqueueZip(Map.of());
        enqueueZip(Map.of());
        DartCorpCodeCache cache = cache(2);
        assertThat(cache.findCorpCode("005930")).contains("00126380");

        clock.advance(Duration.ofHours(25));
        assertThat(cache.findCorpCode("005930")).as("옛 판에 있는 것은 그대로 찾는다").contains("00126380");
        assertThatThrownBy(() -> cache.findCorpCode("035420"))
                .isInstanceOfSatisfying(DataSourceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT);
                    assertThat(e.getMessage()).contains("미매핑");
                    assertThat(MarketCompleteness.sourceAnswered(e.kind().name(), e.sourceAnswered(), true))
                            .as("답 못 받음 — 그 종목의 시장(KOSPI)이 미완료로 판정된다").isFalse();
                });
        assertThat(server.getRequestCount()).as("첫 적재 1 + 다시 받기 1 + 즉시 재시도 1").isEqualTo(3);

        cache.findCorpCode("005930");
        assertThat(server.getRequestCount()).as("10분 기억 — 콜 0").isEqualTo(3);
    }

    @Test
    @DisplayName("첫 적재가 014면 종목 조회는 NOT_FOUND가 아니라 TRANSIENT — 「DART가 모른다」로 읽히지 않게 · 10분 기억")
    void firstLoadNoDataIsTransientAndRemembered() {
        enqueueJson("{\"status\":\"014\",\"message\":\"파일이 존재하지 않습니다.\"}");
        enqueueJson("{\"status\":\"014\",\"message\":\"파일이 존재하지 않습니다.\"}");
        DartCorpCodeCache cache = cache(2);

        assertThatThrownBy(() -> cache.findCorpCode("005930"))
                .isInstanceOfSatisfying(DataSourceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT);
                    assertThat(e.getMessage()).contains("014");
                });
        assertThatThrownBy(() -> cache.findCorpCode("000660")).isInstanceOf(DataSourceException.class);
        assertThat(server.getRequestCount()).as("즉시 재시도 한 번 뒤로는 기억").isEqualTo(2);
    }

    @Test
    @DisplayName("인증 실패(010)는 감싸지 않고 다시 묻지 않는다 — AUTH는 배치를 세워야 한다")
    void authIsNotWrappedNorRetried() {
        enqueueJson("{\"status\":\"010\",\"message\":\"등록되지 않은 키입니다.\"}");
        DartCorpCodeCache cache = cache(2);

        assertThatThrownBy(() -> cache.findCorpCode("005930"))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.AUTH));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("5xx·잘린 ZIP은 받기 실패(TRANSIENT) — 상태를 먼저 본다 · 순간 실패 하나는 즉시 재시도가 흡수한다")
    void transportFailuresAreTransientAndRetriedOnce() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("<html>점검</html>"));
        enqueueZip(Map.of("005930", "00126380", "000660", "00164779"));
        DartCorpCodeCache cache = cache(2);
        assertThat(cache.findCorpCode("005930")).contains("00126380");
        assertThat(server.getRequestCount()).isEqualTo(2);

        DartCorpCodeCache truncated = cache(2);
        byte[] zip = zip(Map.of("005930", "00126380", "000660", "00164779"));
        byte[] cut = java.util.Arrays.copyOf(zip, 10);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(cut)));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(new Buffer().write(cut)));
        assertThatThrownBy(() -> truncated.findCorpCode("005930"))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
    }

    private DartCorpCodeCache cache(int minListed) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.getDart().setBaseUrl(server.url("/").toString());
        properties.getDart().setApiKey("test-key");
        return new DartCorpCodeCache(new ExternalApiClientFactory(), properties,
                new TokenBucketRateLimiter("dart", 0, 0), minListed, clock);
    }

    private void enqueueZip(Map<String, String> listed) throws IOException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/x-msdownload")
                .setBody(new Buffer().write(zip(listed))));
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json;charset=UTF-8").setBody(body));
    }

    /** 상장사만 든 CORPCODE.xml을 ZIP으로 싼다. 비상장 법인 하나를 함께 넣는다(stock_code가 빈 행 — 거르는지). */
    private static byte[] zip(Map<String, String> listed) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><result>");
        listed.forEach((stock, corp) -> xml.append("<list><corp_code>").append(corp)
                .append("</corp_code><corp_name>회사</corp_name><stock_code>").append(stock)
                .append("</stock_code><modify_date>20260101</modify_date></list>"));
        xml.append("<list><corp_code>00000001</corp_code><corp_name>비상장</corp_name><stock_code> </stock_code></list>");
        xml.append("</result>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("CORPCODE.xml"));
            zip.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** 시험이 손으로 미는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
