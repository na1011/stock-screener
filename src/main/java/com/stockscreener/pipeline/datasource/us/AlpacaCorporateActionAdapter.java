package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.CorporateActionDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.PriceDiscontinuity;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 미국 기업 행위. Alpaca Market Data API의 {@code /v1/corporate-actions}를 쓴다(ADR-0090).
 *
 * <p>시세 어댑터와 클래스를 나눈 것은 ADR-0006의 MUST다 — 한 벤더가 여러 종류를 제공해도
 * 어댑터를 나눈다. base URL은 시세와 같은 {@code data.alpaca.markets}이고 자산 마스터
 * ({@code paper-api.alpaca.markets})와 다르다.
 *
 * <h2>국내 어댑터와 결정적으로 다른 것</h2>
 * <p>DART는 비율을 안 줘서 {@code price_daily.listed_shares} 배수에서 유도하지만, 이쪽은
 * {@code old_rate}/{@code new_rate}를 직접 준다. 그래서 {@code StoredPriceSeries} 유도 계층이
 * 없다 — <b>그리고 쓸 수도 없다.</b> 미국 봉은 {@code listed_shares}가 전 행 NULL이다.
 * 즉 <b>이 어댑터에는 소스를 검산할 수단이 없고</b>, 그 자리를 ADR-0090의 premise가 대신한다
 * (계수를 적용한 뒤 {@code adj_close}에 불연속이 남는지 본다).
 *
 * <h2>MUST: 요청 창과 필터 기준이 다르다</h2>
 * <p>{@code start}/{@code end}는 <b>{@code process_date}</b> 기준인데 포트는
 * <b>{@code ex_date}</b> 기준 필터를 요구한다. 공급자가 <i>"기업행위의 생성 시점을 보장하지
 * 않는다"</i>고 공식 경고했고, 실측으로 <b>둘이 3년 넘게 벌어진 사건이 있다</b>
 * (T→WBD 분사: {@code ex_date} 2022-04-11 / {@code process_date} 2025-07-18).
 * 그래서 <b>넓게 부르고 {@code ex_date}로 거른다.</b> 좁게 부르면 사건이 빠지는데,
 * 빠진 것은 「그 기간에 없다」와 같은 모양이다.
 *
 * <h2>MUST: 페이지를 끝까지 돈다</h2>
 * <p>{@code limit} 기본이 <b>100</b>이고 <b>전체 기준</b>이며(실측), 정렬이 문서 서술
 * ({@code process_date})과 달리 <b>심볼 우선</b>이다(실측: 11종목 80건을 {@code limit=2}로
 * 받으면 40페이지이고 심볼이 블록으로 나온다). 시세와 같은 함정이라 <b>중간에 멈추면 뒤쪽
 * 심볼이 통째로 빈다.</b>
 *
 * <h2>담는 것과 안 담는 것</h2>
 * <p>계수를 낼 수 있는 <b>분할·병합만</b> 값으로 낸다(ADR-0090). 배당·분사·합병류는
 * <b>안 담고 세어서 로그로 남긴다</b> — 「그런 사건이 없다」와 「우리가 안 담는다」는 다르다.
 * 그리고 <b>16종 밖의 처음 보는 타입이 오면 WARN으로 알린다.</b> 안 알리면 소스가 새 타입을
 * 주기 시작해도 조용히 넘어간다({@code GovernanceDeriveJob}이 모르는 {@code metric_key}에
 * 하는 것과 같은 자리다).
 */
@Component
@ConditionalOnApiKey("alpaca")
public class AlpacaCorporateActionAdapter implements CorporateActionDataSource {

    static final String SOURCE_NAME = "alpaca:corporate-action";

    /**
     * 값으로 내는 타입. 봉투의 <b>복수형 키</b>가 그대로 들어 있다.
     *
     * <p>MUST NOT: 배당을 여기 더하지 않는다. {@code CorporateAction.ratio}의 계약이
     * <i>"1주가 몇 주가 되었는가"</i>이고 적용부가 {@code 1/ratio}만 쓰는데, 배당의 조정은
     * 곱셈이 아니라 차감({@code (P−D)/P})이라 <b>표현할 자리가 없다.</b> 배당액의 자리는
     * 거버넌스 축({@code governance_fact.CASH_DIVIDEND_TOTAL})이다.
     */
    private static final Map<String, CorporateAction.Type> ADJUSTING = Map.of(
            "forward_splits", CorporateAction.Type.SPLIT,
            "reverse_splits", CorporateAction.Type.REVERSE_SPLIT);

    /**
     * 공급자가 문서로 밝힌 16종(단수형). <b>여기 없는 키가 오면 스펙이 넓어진 것</b>이므로
     * WARN으로 알린다. 봉투 키는 복수형이라 뒤의 {@code s}를 떼고 맞춘다.
     */
    private static final Set<String> KNOWN_TYPES = Set.of(
            "forward_split", "reverse_split", "unit_split",
            "cash_dividend", "stock_dividend", "spin_off",
            "cash_merger", "stock_merger", "stock_and_cash_merger",
            "redemption", "name_change", "worthless_removal",
            "rights_distribution", "partial_call", "reorganization",
            "capital_gains_distribution");

    /** 최대치. 기본 100은 한 종목 몇 해치로도 닿는다. */
    private static final int PAGE_LIMIT = 1000;

    /** 페이지 순회 상한. 무한 루프를 막는 안전장치이고 정상 경로에서는 닿지 않는다. */
    private static final int MAX_PAGES = 50;

    /**
     * {@code start}를 {@code from}보다 얼마나 앞당길 것인가.
     *
     * <p>요청 창이 {@code process_date} 기준이므로, <b>효력일보다 먼저 처리된 사건</b>
     * (사전 공시)을 놓치지 않으려면 앞으로 넉넉해야 한다. 실측한 것은 반대 방향의 지연
     * (최대 3년 3개월)이고 <b>선행 폭은 재지 않았다</b> — 분할 공시가 대개 몇 주~몇 달 앞선다는
     * 통념에 1년은 넉넉하다. 넓혀도 비용은 페이지 수뿐이다.
     */
    private static final int REQUEST_LEAD_YEARS = 1;

    private static final Logger log = LoggerFactory.getLogger(AlpacaCorporateActionAdapter.class);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean credentialsPresent;
    private final int maxPages;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public AlpacaCorporateActionAdapter(ExternalApiClientFactory clientFactory,
                                        DataSourceProperties properties,
                                        @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter, properties.getAlpacaMarketDataBaseUrl(),
                MAX_PAGES);
    }

    // MockWebServer 테스트 전용 — base URL과 페이지 상한을 바꿔 끼운다.
    AlpacaCorporateActionAdapter(ExternalApiClientFactory clientFactory,
                                 DataSourceProperties properties,
                                 RateLimiter rateLimiter,
                                 String baseUrl,
                                 int maxPages) {
        DataSourceProperties.Source source = properties.getAlpaca();
        this.client = clientFactory.create(source, baseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.credentialsPresent = source.hasApiKey() && source.hasSecretKey();
        this.maxPages = maxPages;
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of(UsMarket.CODE);
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public List<CorporateAction> fetchCorporateActions(SecurityRef security, LocalDate from,
                                                       LocalDate to) throws DataSourceException {
        requireCredentials();
        if (from == null || to == null || from.isAfter(to)) {
            // 호출 전에 막는다. 뒤집힌 구간은 소스가 빈 결과로 돌려줄 수 있고, 그러면
            // "그 기간에 사건이 없다"와 구별되지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "구간이 뒤집혔거나 비었다: " + from + " ~ " + to);
        }

        String symbol = security.ticker();
        // process_date 창이다. 아래에서 ex_date로 다시 거른다.
        LocalDate requestStart = from.minusYears(REQUEST_LEAD_YEARS);
        LocalDate requestEnd = maxOf(to, LocalDate.now());

        List<CorporateAction> actions = new ArrayList<>();
        Map<String, Integer> skipped = new TreeMap<>();
        Set<String> unknownTypes = new LinkedHashSet<>();
        String pageToken = null;
        int page = 0;
        do {
            String context = "corporate-actions %s %s~%s page=%d"
                    .formatted(symbol, requestStart, requestEnd, page + 1);
            ResponseEntity<String> response =
                    call(symbol, requestStart, requestEnd, pageToken, context);
            AlpacaResponse.Envelope envelope = AlpacaResponse.requireCorporateActions(
                    response.getStatusCode(), response.getBody(), SOURCE_NAME, context);

            for (Map.Entry<String, JsonNode> bucket : envelope.value().properties()) {
                collect(bucket, symbol, from, to, context, actions, skipped, unknownTypes);
            }

            pageToken = envelope.nextPageToken();
            page++;
            if (page >= maxPages && pageToken != null) {
                // MUST NOT: 부분 결과를 반환하지 않는다. 호출자가 반환 목록을 완전한 구간으로
                // 취급하므로, 잘린 목록은 "그 기간에 사건이 없다"와 구별되지 않는다.
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "페이지가 %d를 넘는다 — 상한을 걸고 부분 결과를 버린다. %s"
                                .formatted(maxPages, context));
            }
        } while (pageToken != null);

        if (!unknownTypes.isEmpty()) {
            // 소스의 스펙이 넓어졌다는 신호다. 조용히 넘기면 새 타입이 영영 안 보인다.
            log.warn("처음 보는 기업 행위 타입이 왔다. ticker={} 타입={} — 담을지 판정해야 한다"
                    + "(지금은 안 담고 세기만 했다).", symbol, unknownTypes);
        }
        if (!skipped.isEmpty()) {
            // 「그런 사건이 없다」와 「우리가 안 담는다」를 가르는 유일한 흔적이다.
            log.info("담지 않은 기업 행위. ticker={} {} — 계수를 낼 수 있는 것만 값으로 낸다.",
                    symbol, skipped);
        }
        actions.sort(Comparator.comparing(CorporateAction::effectiveDate));
        return actions;
    }

    /** 봉투의 타입 버킷 하나를 훑는다. */
    private void collect(Map.Entry<String, JsonNode> bucket, String symbol, LocalDate from,
                         LocalDate to, String context, List<CorporateAction> actions,
                         Map<String, Integer> skipped, Set<String> unknownTypes)
            throws DataSourceException {
        String key = bucket.getKey();
        if (!bucket.getValue().isArray()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "타입 '" + key + "'의 값이 배열이 아니다. " + context);
        }
        CorporateAction.Type type = ADJUSTING.get(key);
        if (type == null) {
            if (!KNOWN_TYPES.contains(singular(key))) {
                unknownTypes.add(key);
            }
            skipped.merge(key, bucket.getValue().size(), Integer::sum);
            return;
        }
        for (JsonNode node : bucket.getValue()) {
            CorporateAction action = toAction(node, type, key, symbol, context);
            if (action == null) {
                continue;
            }
            // MUST: 포트가 [from, to]를 effectiveDate 기준으로 거르라고 요구한다. 요청 창은
            // process_date 기준이라 이것을 안 하면 창 밖 사건이 매 실행 다시 올라온다.
            if (action.effectiveDate().isBefore(from) || action.effectiveDate().isAfter(to)) {
                continue;
            }
            actions.add(action);
        }
    }

    /** 복수형 봉투 키에서 단수형 타입 이름을 낸다. */
    private static String singular(String bucketKey) {
        return bucketKey.endsWith("s") ? bucketKey.substring(0, bucketKey.length() - 1) : bucketKey;
    }

    /**
     * 사건 하나를 옮긴다. 우리 종목이 아니면 {@code null}.
     *
     * <p>{@code ratio}는 <b>{@code new_rate / old_rate}</b>다 — 실측으로 NVDA 10:1 분할이
     * {@code old=1 / new=10}이고 ATRA 25:1 병합이 {@code old=25 / new=1}이라 분할이 1보다 크고
     * 병합이 1보다 작다. <b>이 방향이 이 어댑터의 유일한 무증상 버그 자리다</b> — 뒤집으면
     * 과거 시세가 배수의 제곱만큼 틀리고 값은 그럴듯하다(ADR-0049).
     *
     * <p>MUST: <b>스케일을 지정해 나눈다.</b> 3:1 병합이면 {@code 1/3}이라 스케일 없는
     * {@code divide}는 {@code ArithmeticException}을 던지는데, 그것은 종목 실패가 아니라
     * <b>배치 전체의 죽음</b>이다({@code failUnexpectedly} 뒤 되던져진다).
     */
    private CorporateAction toAction(JsonNode node, CorporateAction.Type type, String bucketKey,
                                     String symbol, String context) throws DataSourceException {
        String rowSymbol = node.path("symbol").asString("");
        if (!symbol.equals(rowSymbol)) {
            // 분사는 symbol이 아니라 source_symbol/new_symbol을 쓴다(실측). 분할·병합에서
            // 이것이 어긋나면 우리가 안 물어본 종목이므로 붙이지 않는다 — 붙이면 엉뚱한
            // 종목의 계수가 아무 에러 없이 틀린다.
            log.warn("요청한 종목과 다른 심볼의 사건이라 건너뛴다. 요청={} 응답={} 타입={}. {}",
                    symbol, rowSymbol.isBlank() ? "(없음)" : rowSymbol, bucketKey, context);
            return null;
        }

        LocalDate exDate = dateOf(node, "ex_date", context);
        BigDecimal oldRate = decimalOf(node, "old_rate", context);
        BigDecimal newRate = decimalOf(node, "new_rate", context);
        if (oldRate.signum() <= 0 || newRate.signum() <= 0) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "비율이 양수가 아니다: old=%s new=%s. %s".formatted(oldRate, newRate, context));
        }
        BigDecimal ratio = newRate.divide(oldRate, PriceDiscontinuity.RATIO_SCALE, RoundingMode.HALF_UP);

        // MUST: 부호를 여기서 직접 본다. CorporateAction.Type.allows는 패키지 프라이빗이고,
        // 넓히면 ADR-0050이 경고한 자리가 열린다 — 그것은 부호만 보고 양수를 안 본다.
        boolean signMatches = type == CorporateAction.Type.SPLIT
                ? ratio.compareTo(BigDecimal.ONE) > 0
                : ratio.compareTo(BigDecimal.ONE) < 0;
        if (!signMatches) {
            // 소스의 낱말과 값이 어긋난 것이다. 지어내지 않고 종목 실패로 남긴다 —
            // 컴팩트 생성자에 그대로 넘기면 RuntimeException이 되어 배치 전체가 죽는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "타입과 비율의 부호가 어긋난다: %s ratio=%s (old=%s new=%s). %s"
                            .formatted(bucketKey, ratio, oldRate, newRate, context));
        }

        // 분할·병합은 권리락과 신주상장이 같은 날이다(실측: NVDA·ATRA 모두 payable_date = ex_date).
        // 그래도 소스가 준 값을 그대로 싣는다 — 우리가 같다고 가정한 것이 아니게 하려는 것이다.
        LocalDate payable = optionalDateOf(node, "payable_date");
        return new CorporateAction(exDate, payable, exDate, type, ratio,
                CorporateAction.DetectionSource.SOURCE_DISCLOSURE, singular(bucketKey));
    }

    private static LocalDate maxOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private LocalDate dateOf(JsonNode node, String field, String context)
            throws DataSourceException {
        LocalDate value = optionalDateOf(node, field);
        if (value == null) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "필수 날짜가 없다: " + field + ". " + context);
        }
        return value;
    }

    private static LocalDate optionalDateOf(JsonNode node, String field) {
        String raw = node.path(field).asString("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private BigDecimal decimalOf(JsonNode node, String field, String context)
            throws DataSourceException {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "필수 필드가 없다: " + field + ". " + context);
        }
        try {
            // 문자열로 받아 스케일을 보존한다. double을 거치면 소수 자릿수가 흔들린다.
            return new BigDecimal(value.asString());
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "수로 읽을 수 없다: " + field + "=" + value + ". " + context, e);
        }
    }

    private ResponseEntity<String> call(String symbol, LocalDate start, LocalDate end,
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

        try {
            return client.get()
                    .uri(uri -> {
                        // MUST: 전부 명시한다. start·end의 기본값이 둘 다 「당일」이고 limit
                        // 기본이 100이라, 기본값에 기대면 조용히 잘린 결과가 온다(실측).
                        uri.path("/v1/corporate-actions")
                                .queryParam("symbols", symbol)
                                // MUST NOT: types로 좁히지 않는다. 좁히면 담지 않기로 한 종류의
                                // 건수가 아예 안 와서 「그런 사건이 없다」와 「우리가 안 담는다」가
                                // 갈리지 않고(phase 기준 P23 축③), 처음 보는 타입 경보도 영영
                                // 안 뜬다. 값은 실측으로 작다 — 파일럿 11종목 2.7년치가 80건이다.
                                .queryParam("start", start.toString())
                                .queryParam("end", end.toString())
                                .queryParam("data_quality", "complete")
                                .queryParam("region", "us")
                                .queryParam("limit", PAGE_LIMIT)
                                .queryParam("sort", "asc");
                        if (pageToken != null) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    // MUST: 문자열로 받는다 — 401 본문이 nginx HTML이다(AlpacaResponse).
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }
    }

    private void requireCredentials() throws DataSourceException {
        if (!credentialsPresent) {
            // @ConditionalOnApiKey는 api-key 하나만 보므로 시크릿이 비어도 어댑터는 등록된다.
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "APCA_API_KEY_ID와 APCA_API_SECRET_KEY가 둘 다 설정되어야 한다.");
        }
    }
}
