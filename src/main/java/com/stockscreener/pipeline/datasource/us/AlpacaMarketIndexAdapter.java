package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.MarketIndexCodes;
import com.stockscreener.pipeline.datasource.MarketIndexDataSource;
import com.stockscreener.pipeline.datasource.RawIndexBar;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * 미국 시장지수를 <b>SPY로</b> 받는다(ADR-0053의 저장 계약을 그대로 쓴다).
 *
 * <p><b>지수 심볼은 이 소스가 안 준다.</b> 실측으로 {@code ^SPX}·{@code ^GSPC}는 HTTP 400이고
 * {@code SPX}는 <b>200인데 {@code bars}가 빈 객체</b>다 — 「모르는 심볼은 조용히 빠진다」
 * 그 형태라 상태코드만 보면 「받았다」로 읽힌다({@code alpaca-bars.md}).
 *
 * <p><b>대용의 오차를 쟀다.</b> FRED의 실제 S&amp;P 500과 공통 거래일 1,757일을 대조해
 * 52주 수익률 차가 <b>평균 0.03%p</b> · 일간 상관 <b>0.9928</b>이고 차가 0.2%p 미만인 날이
 * 97.1%다. 큰 차는 2020-03과 2025-04에 몰려 있다(종가 산정 시차).
 *
 * <p><b>MUST NOT: FRED로 갈아타지 않는다.</b> 받아지긴 하지만 넷 다
 * <b>Copyrighted: Pre-Approval Required</b>라 spec §2가 미국 시세에 이미 든 것과
 * <b>같은 class의 새 제약</b>이 생기고, S&amp;P·다우 계열은 10년 롤링이라 이력도 계속 잘린다.
 *
 * <p><b>MUST NOT: {@code adjustment}를 {@code raw} 밖으로 바꾸지 않는다.</b> ETF는 배당을
 * 지급하므로 {@code split}·{@code all}로 받으면 <b>과거 종가가 소급해서 달라지고</b>
 * 저장된 지수 시계열이 실행마다 바뀐다 — 그 변화는 아무 에러도 안 낸다.
 */
@ConditionalOnApiKey("alpaca")
@Component
public class AlpacaMarketIndexAdapter implements MarketIndexDataSource {

    private static final Logger log = LoggerFactory.getLogger(AlpacaMarketIndexAdapter.class);

    /**
     * 담기는 값의 출처. <b>{@code index_code}는 {@code US}이고 이것은 심볼이다</b> —
     * 둘을 같게 두면 {@code IndicatorRunner}가 {@code market_code}로 지수를 못 고른다.
     */
    static final String SYMBOL = "SPY";

    /** 실패 기록과 로그에 남는 이름. 시세와 가르는 이유는 실패의 단위가 다르기 때문이다. */
    private static final String SOURCE_NAME = "alpaca:index";

    /** 한 페이지 상한. 지수는 한 심볼뿐이라 시세보다 여유가 크다. */
    private static final int PAGE_LIMIT = 10_000;

    /** 페이지 수 상한. 토큰이 안 끝날 때 무한 루프 대신 실패한다. */
    private static final int MAX_PAGES = 20;

    /**
     * 요청 끝을 <b>현재로부터 이만큼 뒤로</b> 민다. 무료 플랜의 15분 규칙 때문이다.
     *
     * <p><b>MUST: 날짜가 아니라 Instant로 준다.</b> {@code end}에 날짜만 주면 소스가 그날의
     * 끝으로 해석해 UTC로는 미래가 되고, <b>어제 날짜도 403</b>이 난다(실측 2026-08-31:
     * {@code subscription does not permit querying recent SIP data}). 그 403은 {@code AUTH}로
     * 접히면 {@code fatal()}이라 배치 전체를 죽인다 — 가이드가 규칙을 적었지만
     * <b>우리가 보낼 값에 대해 참인지는 안 적었다</b>({@code .claude/rules/data-pipeline.md}).
     */
    private static final Duration END_MARGIN = Duration.ofMinutes(16);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean credentialsPresent;

    public AlpacaMarketIndexAdapter(ExternalApiClientFactory clientFactory,
                                    DataSourceProperties properties,
                                    @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter) {
        DataSourceProperties.Source source = properties.getAlpaca();
        // MUST: AlpacaHeaders를 붙인다. 이 소스는 헤더가 둘이라(APCA-API-KEY-ID ·
        // APCA-API-SECRET-KEY) 기본 create로는 401이 나고, 그 응답이 JSON이 아니라 nginx의
        // HTML이라 상태코드를 안 보면 파싱에서 터진다.
        this.client = clientFactory.create(source, properties.getAlpacaMarketDataBaseUrl(),
                AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        // @ConditionalOnApiKey는 api-key 하나만 보므로 시크릿이 비어도 어댑터는 등록된다.
        this.credentialsPresent = source.hasApiKey() && source.hasSecretKey();
    }

    @Override
    public Set<String> supportedIndexCodes() {
        // MUST: MarketIndexCodes.ALL의 부분집합이어야 한다. 여기 없는 코드를 담당한다고 말하면
        // ck_market_index_code에 걸려 적재가 막힌다.
        if (!MarketIndexCodes.ALL.contains(MarketIndexCodes.US)) {
            throw new IllegalStateException(
                    "MarketIndexCodes.ALL에 US가 없다: " + MarketIndexCodes.ALL);
        }
        return Set.of(MarketIndexCodes.US);
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<RawIndexBar> fetchDailyBars(String indexCode, LocalDate from, LocalDate to)
            throws DataSourceException {
        if (!credentialsPresent) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, sourceName(),
                    "APCA_API_KEY_ID와 APCA_API_SECRET_KEY가 둘 다 설정되어야 한다.");
        }
        if (!MarketIndexCodes.US.equals(indexCode)) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName(),
                    "이 어댑터가 담당하지 않는 지수다: " + indexCode);
        }
        if (from == null || to == null || from.isAfter(to)) {
            // 뒤집힌 구간은 소스가 빈 결과로 돌려줄 수 있고, 그러면 「그 기간에 데이터가
            // 없다」와 구별되지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName(),
                    "구간이 뒤집혔거나 비었다: " + from + " ~ " + to);
        }

        Instant end = clampEnd(to);
        if (!end.isAfter(from.atStartOfDay(ZoneOffset.UTC).toInstant())) {
            // 구간 전체가 마지막 16분 안이다. 닫힌 봉이 있을 수 없으므로 빈 목록이 옳다.
            log.debug("요청 구간이 15분 규칙 안이라 받을 봉이 없다. {} ~ {}", from, to);
            return List.of();
        }

        List<RawIndexBar> bars = new ArrayList<>();
        String pageToken = null;
        int page = 0;
        do {
            String context = "index %s(%s) %s~%s page=%d"
                    .formatted(indexCode, SYMBOL, from, to, page + 1);
            ResponseEntity<String> response = call(from, end, pageToken, context);
            AlpacaResponse.Envelope envelope = AlpacaResponse.requireBars(
                    response.getStatusCode(), response.getBody(), sourceName(), context);
            JsonNode nodes = envelope.value().path(SYMBOL);
            if (page == 0 && nodes.isEmpty() && envelope.nextPageToken() == null) {
                // 모르는 심볼도 HTTP 200 + 빈 목록이다. 조용한 0건으로 넘기면 「지수가 없다」와
                // 「심볼이 틀렸다」가 같은 모양이 된다.
                throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, sourceName(),
                        "심볼 %s의 봉이 한 건도 없다 — 심볼이 바뀌었는지 확인하라. %s"
                                .formatted(SYMBOL, context));
            }
            for (JsonNode node : nodes) {
                bars.add(toBar(node, context));
            }
            pageToken = envelope.nextPageToken();
            page++;
            if (page >= MAX_PAGES && pageToken != null) {
                // MUST NOT: 부분 결과를 반환하지 않는다. 잘린 지수는 그 구간 전 종목의
                // 베타·초과수익을 조용히 판정불가로 만든다.
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName(),
                        "페이지가 %d를 넘는다 — 상한을 걸고 부분 결과를 버린다. %s"
                                .formatted(MAX_PAGES, context));
            }
        } while (pageToken != null);

        log.info("미국 지수를 받았다. index={} symbol={} {}~{} 봉={}",
                indexCode, SYMBOL, from, to, bars.size());
        // 포트가 날짜 오름차순을 요구한다. sort=asc를 주지만 페이지 경계에 기대지 않는다.
        bars.sort(Comparator.comparing(RawIndexBar::tradeDate));
        return bars;
    }

    private static Instant clampEnd(LocalDate to) {
        Instant requested = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant safe = Instant.now().minus(END_MARGIN);
        return requested.isBefore(safe) ? requested : safe;
    }

    private ResponseEntity<String> call(LocalDate from, Instant end, String pageToken,
                                        String context) throws DataSourceException {
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, sourceName(),
                    "호출 대기 중 인터럽트됐다. " + context, e);
        }
        try {
            return client.get()
                    .uri(uri -> {
                        uri.path("/v2/stocks/bars")
                                .queryParam("symbols", SYMBOL)
                                .queryParam("timeframe", "1Day")
                                .queryParam("start", from.toString())
                                .queryParam("end", end.toString())
                                .queryParam("feed", "sip")
                                .queryParam("adjustment", "raw")
                                .queryParam("limit", PAGE_LIMIT)
                                .queryParam("sort", "asc");
                        if (pageToken != null) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    // MUST: 문자열로 받는다 — 오류 본문이 JSON이 아니다(AlpacaResponse).
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, sourceName(),
                    "호출에 실패했다. " + context, e);
        }
    }

    /**
     * 봉 하나를 옮긴다.
     *
     * <p>{@code tradeValue}·{@code constituentCount}는 <b>null이다</b> — ETF 봉에는 없다.
     * MUST NOT: {@code close × volume}으로 거래대금을 합성하지 않는다(V1 스키마의 같은 규칙).
     * {@code volume}은 <b>ETF의 거래량</b>이지 지수 구성종목의 거래량이 아니므로 담되 계산에
     * 쓰지 않는다.
     */
    private RawIndexBar toBar(JsonNode node, String context) throws DataSourceException {
        try {
            // 일봉의 t는 뉴욕 자정을 UTC로 표현한 값이라 시각 부분이 서머타임에 따라 바뀐다.
            LocalDate tradeDate = LocalDate.parse(node.path("t").asString("").substring(0, 10));
            return new RawIndexBar(
                    tradeDate,
                    decimal(node, "o"), decimal(node, "h"), decimal(node, "l"), decimal(node, "c"),
                    node.path("v").asLong(0L),
                    null,
                    null);
        } catch (RuntimeException e) {
            // 파싱 실패는 벤더 스펙 변경의 신호다. 조용히 건너뛰지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, sourceName(),
                    "지수 봉을 해석할 수 없다: " + node + ". " + context, e);
        }
    }

    /** double을 거치지 않는다 — 지수 종가는 소수 둘 이상이고 반올림이 누적된다. */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : new BigDecimal(value.asString());
    }
}
