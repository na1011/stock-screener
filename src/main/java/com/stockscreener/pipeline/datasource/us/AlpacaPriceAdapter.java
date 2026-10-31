package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.PriceDataSource;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 미국 일별 시세. Alpaca Market Data API의 {@code /v2/stocks/bars}를 쓴다.
 *
 * <p>유니버스·종목마스터 어댑터와 클래스를 나눈 것은 ADR-0006의 MUST다 — 한 벤더가 여러
 * 종류를 제공해도 어댑터를 나눈다. 그리고 <b>base URL이 실제로 다르다</b>:
 * 시세는 {@code data.alpaca.markets}, 자산 마스터는 {@code paper-api.alpaca.markets}다.
 * 인증 헤더가 두 계열 모두 같아 섞으면 <b>404가 「키 문제」로 보이지 않는다.</b>
 *
 * <h2>파라미터를 전부 명시하는 이유</h2>
 * <ul>
 *   <li>{@code feed=sip} — 기본값 서술이 문서끼리 어긋난다. 빠뜨리면 오류 없이 단일 거래소
 *       (시장 거래량의 약 2.5%) 데이터를 받고, 고가·저가를 쓰는 지표가 조용히 오염된다.</li>
 *   <li>{@code adjustment=raw} — 소스가 계산한 수정주가를 받지 않는다(ADR-0006). 우리가
 *       원장에서 계수를 짓는다(ADR-0049). 실측으로 raw가 분할 전 값임을 확인했다
 *       (NVDA 2024-05-01 종가 raw 830.41 / all 82.90).</li>
 *   <li>{@code limit} — 기본 1000이고 <b>심볼별이 아니라 전체 데이터 포인트 기준</b>이다.</li>
 * </ul>
 *
 * <h2>IMPORTANT — {@code end}를 시각으로 클램프한다</h2>
 * <p>15분 규칙에 걸리면 403이 오고, {@link AlpacaResponse#kindOf}가 그것을 {@code AUTH}로
 * 접으며 {@code AUTH}는 {@code fatal()}이다 — <b>종목 하나가 아니라 배치 전체가 죽는다.</b>
 * 미국이 일일 갱신에 들어온 뒤라면 국내 수집까지 함께 멈춘다.
 *
 * <p>그래서 <b>날짜를 그대로 보내지 않는다.</b> 실측(2026-08-29T02:52Z): 날짜만 주는 {@code end}는
 * <b>어제 날짜도 403</b>이다 — 뉴욕 기준 그날의 끝으로 해석되어 UTC로는 아직 미래이기 때문이다.
 * {@code now - 16분}을 RFC-3339로 주면 200이고, 그때 <b>어제 봉까지 온다.</b>
 *
 * <p>MUST NOT: 이 403을 {@code MALFORMED}로 낮춰 「고치지」 않는다. 그러면 종목마다 조용히
 * 실패하고 배치는 부분 성공으로 끝난다 — 원인이 우리 요청이라는 사실이 묻힌다.
 *
 * <h2>이 소스가 갈라 주지 않는 것</h2>
 * <p>모르는 심볼은 <b>HTTP 200 응답에서 조용히 빠진다</b>(실측). 즉 「그 구간에 봉이 없다」와
 * 「종목 자체를 모른다」가 응답상 같다. 포트는 그 둘을 다르게 정의하지만
 * ({@code NOT_FOUND} vs 빈 목록) 갈 근거가 없으므로 <b>빈 목록</b>을 반환한다.
 * 계약과 실측은 {@code docs/api-spec/alpaca-bars.md}에 있다.
 */
@Component
@ConditionalOnApiKey("alpaca")
public class AlpacaPriceAdapter implements PriceDataSource {

    static final String SOURCE_NAME = "alpaca:price";

    /** 최대치. 기본 1000은 한 종목 4년치로도 닿는다. */
    private static final int PAGE_LIMIT = 10_000;

    /**
     * 페이지 순회 상한. 무한 루프를 막는 안전장치이고 정상 경로에서는 닿지 않는다 —
     * 한 종목 1년치가 252봉이라 {@link #PAGE_LIMIT} 한 페이지에 들어온다(실측).
     */
    private static final int MAX_PAGES = 50;

    /** 15분 규칙에 여유 1분. 실측에서 5분 전은 403, 16분 전은 200이었다. */
    private static final Duration END_MARGIN = Duration.ofMinutes(16);

    private static final Logger log = LoggerFactory.getLogger(AlpacaPriceAdapter.class);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean credentialsPresent;
    private final int maxPages;
    /** 미국 관문 설정 — 켜졌을 때만 거래대금({@code vw × v})을 싣는다(ADR-0196 · 관문 재적재가 보존 창을 한 번에 채운다). */
    private final boolean usGate;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public AlpacaPriceAdapter(ExternalApiClientFactory clientFactory,
                              DataSourceProperties properties,
                              @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter,
                              @Value("${stockscreener.gate.us}") boolean usGate) {
        this(clientFactory, properties, rateLimiter, properties.getAlpacaMarketDataBaseUrl(),
                MAX_PAGES, usGate);
    }

    // MockWebServer 테스트 전용 — base URL과 페이지 상한을 바꿔 끼운다.
    // 상한을 주입하는 이유는 51개 응답을 늘어놓지 않고 "부분 결과를 버린다"를 재기 위해서다
    // (AlpacaUniverseAdapter가 하한을 같은 이유로 주입받는다).
    AlpacaPriceAdapter(ExternalApiClientFactory clientFactory,
                       DataSourceProperties properties,
                       RateLimiter rateLimiter,
                       String baseUrl,
                       int maxPages) {
        this(clientFactory, properties, rateLimiter, baseUrl, maxPages, false);
    }

    AlpacaPriceAdapter(ExternalApiClientFactory clientFactory,
                       DataSourceProperties properties,
                       RateLimiter rateLimiter,
                       String baseUrl,
                       int maxPages,
                       boolean usGate) {
        this.usGate = usGate;
        DataSourceProperties.Source source = properties.getAlpaca();
        this.client = clientFactory.create(source, baseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.credentialsPresent = source.hasApiKey() && source.hasSecretKey();
        this.maxPages = maxPages;
    }

    @Override
    public Set<String> supportedMarketCodes() {
        // 미국은 시장이 하나다. 거래소는 stock.listing_exchange가 가른다(ADR-0086).
        return Set.of(UsMarket.CODE);
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<RawPriceBar> fetchDailyBars(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        requireCredentials();
        if (from == null || to == null || from.isAfter(to)) {
            // 호출 전에 막는다. 뒤집힌 구간은 소스가 빈 결과로 돌려줄 수 있고, 그러면
            // "그 기간에 데이터가 없다"와 구별되지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "구간이 뒤집혔거나 비었다: " + from + " ~ " + to);
        }

        // 표지가 아니라 실체로 묻는다(ADR-0180) — 닻 기호 + 닻 날짜의 asof가 개명 뒤에도 같은 실체를 준다(HLX@08-28 = HOS).
        // 응답 키는 요청 기호다(실측 · alpaca-bars.md §asof).
        String symbol = security.barSymbol();
        LocalDate asOf = security.barAsOf();
        Instant end = clampEnd(to);
        Instant windowStart = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (!end.isAfter(windowStart)) {
            // 구간 전체가 마지막 16분 안이다. 닫힌 봉이 있을 수 없으므로 빈 목록이 옳다.
            log.debug("요청 구간이 15분 규칙 안이라 받을 봉이 없다. {} {} ~ {}", symbol, from, to);
            return List.of();
        }

        List<RawPriceBar> bars = new ArrayList<>();
        String pageToken = null;
        int page = 0;
        do {
            String context = "bars %s %s~%s page=%d".formatted(symbol, from, to, page + 1);
            ResponseEntity<String> response = call(symbol, asOf, from, end, pageToken, context);
            AlpacaResponse.Envelope envelope = AlpacaResponse.requireBars(
                    response.getStatusCode(), response.getBody(), SOURCE_NAME, context);
            for (JsonNode node : envelope.value().path(symbol)) {
                bars.add(toBar(node, symbol, context));
            }
            pageToken = envelope.nextPageToken();
            page++;
            if (page >= maxPages && pageToken != null) {
                // MUST NOT: 부분 결과를 반환하지 않는다. PriceCollector가 반환 목록을 완전한
                // 구간으로 취급하므로, 잘린 목록은 "그 기간에 데이터가 없다"와 구별되지 않는다.
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "페이지가 %d를 넘는다 — 상한을 걸고 부분 결과를 버린다. %s"
                                .formatted(maxPages, context));
            }
        } while (pageToken != null);

        // 저장된 기호 시대(ADR-0180 · ADR-0181) — 닻이 옛 기호를 못 잇는 행이 있다(SPHR ← MSGE · UHAL.B ← UHALB: 같은 CUSIP 개명인데
        // 닻 asof가 개명 뒤 봉만 준다). 시대마다 그 기호를 시대 끝 전날 asof로 시대 구간만 받는다 — 시대 끝 뒤의 같은 문자열은 다른 실체일 수
        // 있다(SPHR 개명일에 MSGE 문자열을 분사된 회사가 받았다). 지금 기호의 봉이 같은 날을 덮으면 그쪽이 이긴다. 시대 끝이 요청 창보다 앞이면
        // 부르지 않는다(일일 갱신 창은 콜이 안 는다).
        java.util.Set<LocalDate> covered = new java.util.HashSet<>();
        bars.forEach(b -> covered.add(b.tradeDate()));
        for (SecurityRef.SymbolEra era : security.identity().recordedEras()) {
            LocalDate eraLast = era.validTo().minusDays(1);
            LocalDate eraFrom = era.validFrom() == null || era.validFrom().isBefore(from) ? from : era.validFrom();
            LocalDate eraTo = eraLast.isBefore(to) ? eraLast : to;
            if (eraTo.isBefore(eraFrom) || era.symbol().equals(symbol)) {
                continue;
            }
            for (RawPriceBar bar : eraBars(era.symbol(), eraLast, eraFrom, eraTo)) {
                if (covered.add(bar.tradeDate())) {
                    bars.add(bar);
                }
            }
        }

        // 포트가 날짜 오름차순을 요구한다. sort=asc를 주지만 페이지 경계에 기대지 않는다.
        bars.sort(Comparator.comparing(RawPriceBar::tradeDate));
        return bars;
    }

    /** 옛 기호 시대 한 구간의 봉 — 그 기호를 시대 끝 전날 asof로 푼다. */
    private List<RawPriceBar> eraBars(String symbol, LocalDate asOf, LocalDate from, LocalDate to) throws DataSourceException {
        Instant end = clampEnd(to);
        if (!end.isAfter(from.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            return List.of();
        }
        List<RawPriceBar> bars = new ArrayList<>();
        String pageToken = null;
        int page = 0;
        do {
            String context = "bars(기호 시대) %s@%s %s~%s page=%d".formatted(symbol, asOf, from, to, page + 1);
            ResponseEntity<String> response = call(symbol, asOf, from, end, pageToken, context);
            AlpacaResponse.Envelope envelope = AlpacaResponse.requireBars(
                    response.getStatusCode(), response.getBody(), SOURCE_NAME, context);
            for (JsonNode node : envelope.value().path(symbol)) {
                RawPriceBar bar = toBar(node, symbol, context);
                if (!bar.tradeDate().isAfter(to)) {
                    bars.add(bar);
                }
            }
            pageToken = envelope.nextPageToken();
            page++;
            if (page >= maxPages && pageToken != null) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "페이지가 %d를 넘는다 — 상한을 걸고 부분 결과를 버린다. %s".formatted(maxPages, context));
            }
        } while (pageToken != null);
        return bars;
    }

    /**
     * 실제로 보낼 {@code end}. <b>15분 규칙을 구조적으로 못 넘게 하는 자리다.</b>
     *
     * <p>{@code to}의 봉은 뉴욕 자정을 UTC로 표현한 시각을 갖는다. 그것을 포함하려면 경계가
     * {@code to} 다음 날 자정(UTC)이면 충분하고, 거기에 {@code now - 16분} 상한을 씌운다.
     */
    private static Instant clampEnd(LocalDate to) {
        Instant requested = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant safe = Instant.now().minus(END_MARGIN);
        return requested.isBefore(safe) ? requested : safe;
    }

    private ResponseEntity<String> call(String symbol, LocalDate asOf, LocalDate from, Instant end,
                                          String pageToken, String context)
            throws DataSourceException {
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

        ResponseEntity<String> response;
        try {
            response = client.get()
                    .uri(uri -> {
                        uri.path("/v2/stocks/bars")
                                .queryParam("symbols", symbol)
                                .queryParam("timeframe", "1Day")
                                .queryParam("start", from.toString())
                                .queryParam("end", end.toString())
                                .queryParam("feed", "sip")
                                .queryParam("adjustment", "raw")
                                .queryParam("limit", PAGE_LIMIT)
                                .queryParam("sort", "asc");
                        if (asOf != null) {
                            uri.queryParam("asof", asOf.toString());
                        }
                        if (pageToken != null) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    // MUST: 문자열로 받는다 — 오류 본문이 JSON이 아니다(AlpacaResponse).
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }
        return response;
    }

    private void requireCredentials() throws DataSourceException {
        if (!credentialsPresent) {
            // @ConditionalOnApiKey는 api-key 하나만 보므로 시크릿이 비어도 어댑터는 등록된다.
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "APCA_API_KEY_ID와 APCA_API_SECRET_KEY가 둘 다 설정되어야 한다.");
        }
    }

    /**
     * 봉 하나를 옮긴다.
     *
     * <p><b>{@code tradeValue}는 관문 켬에서 {@code vw × v}다</b>(ADR-0196) — 그 봉에 든 체결 전부의 Σ가격×수량(VWAP의 정의)이라
     * 「소스가 주는 그날 체결 금액 총계」이고 합성이 아니다. 거래량이 0이면 0(국내 정지 봉이 0으로 오는 것과 같다 · 거래 드문 클래스주),
     * 거래량이 있는데 {@code vw}가 없거나 0 이하면 null(못 받은 것). 꺼짐은 null 그대로다.
     * MUST NOT: {@code close × volume}으로 합성하지 않는다 — 정규장 종가 × 전 세션 거래량이라 다른 값이다.
     * 정규장으로 자르는 길(분봉 합 · 30분 봉 합)은 버렸다 — 종가 경매(하루 거래량의 10~15%)가 16:00~16:03 ET에 찍혀 경계 밖에
     * 떨어지고 봉에 안 실리는 체결도 있다(ADR-0196 §버린 대안 · {@code AlpacaClosingAuctionProbeLiveTest}).
     * {@code listedShares}는 응답에 아예 없다(ADR-0013).
     *
     * <p>{@code splitAdjustedBySource = false}인 근거는 {@code adjustment=raw}다.
     * 그 파라미터를 바꾸면 이 값도 함께 바꿔야 하고, 안 바꾸면 원장 계수가 두 번 적용되어
     * <b>분할비의 제곱만큼</b> 틀린다 — 그리고 그 값은 그럴듯하다.
     */
    private RawPriceBar toBar(JsonNode node, String symbol, String context)
            throws DataSourceException {
        try {
            // 일봉의 t는 뉴욕 자정을 UTC로 표현한 값이라 시각 부분이 서머타임에 따라 바뀐다.
            // 날짜만 취하면 결과가 같고, 시각을 파싱하는 코드는 그 이동을 가정하면 안 된다.
            LocalDate tradeDate = LocalDate.parse(node.path("t").asString("").substring(0, 10));
            return new RawPriceBar(
                    tradeDate,
                    decimal(node, "o"), decimal(node, "h"), decimal(node, "l"), decimal(node, "c"),
                    node.path("v").asLong(0L),
                    usGate ? tradeValueOf(node) : null,
                    null,
                    false);
        } catch (RuntimeException e) {
            // 파싱 실패는 벤더 스펙 변경의 신호다. 조용히 건너뛰지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "봉을 해석할 수 없다: " + symbol + " " + node + ". " + context, e);
        }
    }

    /** 거래대금 {@code vw × v} — 거래량 0이면 0 · 거래량이 있는데 {@code vw}가 없거나 0 이하면 null. */
    private static BigDecimal tradeValueOf(JsonNode node) {
        long volume = node.path("v").asLong(0L);
        if (volume == 0L) {
            return BigDecimal.ZERO;
        }
        JsonNode vw = node.path("vw");
        if (vw.isMissingNode() || vw.isNull()) {
            return null;
        }
        BigDecimal vwap = new BigDecimal(vw.asString());
        if (vwap.signum() <= 0) {
            return null;
        }
        // trade_value는 numeric(20,2)다 — 저장 자리에 맞춰 둘째 자리에서 반올림한다.
        return vwap.multiply(BigDecimal.valueOf(volume)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("필수 필드가 없다: " + field);
        }
        // 문자열로 받아 스케일을 보존한다. double을 거치면 소수 자릿수가 흔들린다.
        return new BigDecimal(value.asString());
    }
}
