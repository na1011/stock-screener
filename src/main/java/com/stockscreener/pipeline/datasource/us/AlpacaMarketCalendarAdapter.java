package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.MarketCalendarDataSource;
import com.stockscreener.pipeline.datasource.MarketSession;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 거래소 세션 달력을 Alpaca에서 받는다(ADR-0091). 계약과 실측은
 * {@code docs/api-spec/alpaca-calendar.md}가 답한다.
 *
 * <p><b>Trading API이고 경로가 {@code v3}다.</b> 시세·기업행위는 {@code data.alpaca.markets}인데
 * 이쪽은 {@code paper-api.alpaca.markets}이며 <b>섞으면 404다</b>(실측). 인증 헤더는 두 계열이
 * 같아 키가 틀린 것처럼 보이지 않는다.
 *
 * <p><b>페이징이 없다.</b> {@code next_page_token} 키 자체가 오지 않고, 2016~2026 11년치가
 * 2,765세션 한 응답으로 온다(실측 805KB). 그래서 순회 루프를 두지 않는다 —
 * 없는 것을 도는 코드는 도는지 확인할 방법도 없다.
 *
 * <p><b>MUST: {@code start}·{@code end}를 둘 다 명시한다.</b> {@code end} 기본값이
 * {@code start + 1주}라 빠뜨리면 <b>절단이 아니라 정상 응답으로</b> 1주만 온다 — 경고도
 * 토큰도 없다.
 *
 * <p><b>MUST NOT: 미래 세션을 그대로 믿지 않는다.</b> 2030년부터 소스가 휴장일을 모른다
 * (실측: 알려진 휴장이 2029년 10일 → 2030년 1일 → 2031년 0일). 그 구간은 평일을 그대로 내므로
 * <b>크리스마스가 개장일이 된다.</b> 창의 끝을 막는 것은 잡의 몫이고, 이 어댑터는 받은 것을
 * 그대로 낸다 — 자르는 자리가 둘이면 어느 쪽이 잘랐는지 알 수 없게 된다.
 */
@Component
@ConditionalOnApiKey("alpaca")
public class AlpacaMarketCalendarAdapter implements MarketCalendarDataSource {

    static final String SOURCE_NAME = "alpaca:calendar";

    /**
     * 우리가 쓰는 거래소 코드. {@code stock.listing_exchange}·{@code ck_market_calendar_exchange}와
     * 같은 값 집합이어야 조인이 성립한다.
     *
     * <p>소스는 {@code XNYS}·{@code XNAS}(MIC)도 받지만 쓰지 않는다 — 우리 조인 키가 아니다.
     * <b>소문자는 400이다</b>(실측: {@code invalid market: nyse}).
     */
    private static final Set<String> EXCHANGES = Set.of("NYSE", "NASDAQ");

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean credentialsPresent;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public AlpacaMarketCalendarAdapter(ExternalApiClientFactory clientFactory,
                                       DataSourceProperties properties,
                                       @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter) {
        // MUST: getAlpaca().getBaseUrl()이다 — 그것이 Trading API다.
        // getAlpacaMarketDataBaseUrl()을 쓰면 404이고, 404는 NOT_FOUND라 "그 거래소를 모른다"로
        // 읽혀 우리 오타가 소스 탓으로 기록된다.
        this(clientFactory, properties, rateLimiter, properties.getAlpaca().getBaseUrl());
    }

    // MockWebServer 테스트 전용 — base URL을 바꿔 끼운다.
    AlpacaMarketCalendarAdapter(ExternalApiClientFactory clientFactory,
                                DataSourceProperties properties,
                                RateLimiter rateLimiter,
                                String baseUrl) {
        DataSourceProperties.Source source = properties.getAlpaca();
        this.client = clientFactory.create(source, baseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.credentialsPresent = source.hasApiKey() && source.hasSecretKey();
    }

    @Override
    public Set<String> supportedExchangeCodes() {
        return EXCHANGES;
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<MarketSession> fetchSessions(String exchangeCode, LocalDate from, LocalDate to)
            throws DataSourceException {
        requireCredentials();
        requireWindow(exchangeCode, from, to);

        String context = "calendar %s %s~%s".formatted(exchangeCode, from, to);
        ResponseEntity<String> response = call(exchangeCode, from, to, context);
        JsonNode calendar = AlpacaResponse.requireCalendar(
                response.getStatusCode(), response.getBody(), SOURCE_NAME, context);

        List<MarketSession> sessions = new ArrayList<>(calendar.size());
        for (JsonNode day : calendar) {
            sessions.add(toSession(day, exchangeCode, context));
        }
        return sessions;
    }

    /**
     * 하루를 옮긴다.
     *
     * <p>MUST: {@code sessionDate}는 소스의 {@code date}를 그대로 쓴다. {@code core_start}에서
     * 유도하면 UTC 환산이 뉴욕 오후를 다음 날로 민다.
     */
    private MarketSession toSession(JsonNode day, String exchangeCode, String context)
            throws DataSourceException {
        LocalDate date = requiredDate(day, "date", context);
        Instant start = requiredInstant(day, "core_start", context);
        Instant end = requiredInstant(day, "core_end", context);
        try {
            return new MarketSession(exchangeCode, date, start, end);
        } catch (IllegalArgumentException e) {
            // 컴팩트 생성자가 부호를 막는다. 여기서 접지 않으면 RuntimeException이 잡을 죽인다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "세션을 만들 수 없다: %s. %s".formatted(e.getMessage(), context), e);
        }
    }

    private static LocalDate requiredDate(JsonNode day, String field, String context)
            throws DataSourceException {
        String raw = day.path(field).asString("");
        if (raw.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "필수 필드가 없다: " + field + ". " + context);
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "날짜로 읽을 수 없다: %s=%s. %s".formatted(field, raw, context), e);
        }
    }

    /**
     * 오프셋이 붙은 시각을 절대 시각으로 접는다.
     *
     * <p>소스가 {@code -05:00}·{@code -04:00}을 스스로 갈아 주므로(실측) 서머타임 처리를
     * 우리가 하지 않는다. 벽시계 시각이 필요하면 저장 뒤 거래소 시간대로 다시 환산한다.
     */
    private static Instant requiredInstant(JsonNode day, String field, String context)
            throws DataSourceException {
        String raw = day.path(field).asString("");
        if (raw.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "필수 필드가 없다: " + field + ". " + context);
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "시각으로 읽을 수 없다: %s=%s. %s".formatted(field, raw, context), e);
        }
    }

    /** 콜을 태우기 전에 막는다. 뒤집힌 창은 소스도 400을 주지만 그것은 쿼터를 쓴 뒤다. */
    private void requireWindow(String exchangeCode, LocalDate from, LocalDate to)
            throws DataSourceException {
        if (exchangeCode == null || !EXCHANGES.contains(exchangeCode)) {
            throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, SOURCE_NAME,
                    "모르는 거래소다: " + exchangeCode + " — 아는 것: " + EXCHANGES);
        }
        if (from == null || to == null) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "조회 창이 비었다: %s~%s. exchange=%s".formatted(from, to, exchangeCode));
        }
        if (from.isAfter(to)) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "조회 창이 뒤집혔다: %s~%s. exchange=%s".formatted(from, to, exchangeCode));
        }
    }

    private void requireCredentials() throws DataSourceException {
        if (!credentialsPresent) {
            // @ConditionalOnApiKey는 api-key 하나만 보므로 시크릿이 비어도 어댑터는 등록된다.
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "APCA_API_KEY_ID와 APCA_API_SECRET_KEY가 둘 다 설정되어야 한다.");
        }
    }

    private ResponseEntity<String> call(String exchangeCode, LocalDate from, LocalDate to,
                                        String context) throws DataSourceException {
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED, SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출 대기 중 인터럽트됐다. " + context, e);
        }

        try {
            return client.get()
                    .uri(uri -> uri.path("/v3/calendar/{market}")
                            // MUST: 둘 다 명시한다. end 기본이 start + 1주라 빠뜨리면
                            // 절단이 아니라 정상 응답으로 1주만 온다(실측).
                            .queryParam("start", from.toString())
                            .queryParam("end", to.toString())
                            .build(exchangeCode))
                    .retrieve()
                    // MUST: 문자열로 받는다 — 이 엔드포인트의 401 본문이 nginx HTML이라
                    // JsonNode로 받으면 상태코드를 보기 전에 터지고 AUTH가 TRANSIENT가 된다.
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }
    }
}
