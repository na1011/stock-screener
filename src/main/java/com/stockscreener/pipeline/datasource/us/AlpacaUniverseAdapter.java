package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.UniverseDataSource;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 미국 유니버스를 Alpaca의 자산 마스터에서 만든다. 계약은
 * {@code docs/api-spec/alpaca-universe.md}, 결정은 ADR-0086이다.
 *
 * <p><b>지수 편입이 아니라 거래소 상장이 기준이다.</b> 지수 편입 이력을 무료로 구할 경로가
 * 없어 리밸런싱 배치의 전제가 성립하지 않는다. 국내가 시총 상위 N인 것과 기준이 달라지는
 * 것은 의도된 비대칭이다.
 *
 * <p><b>적재는 넓게, 노출은 좁게.</b> 여기서 ETF·REIT·우선주·워런트를 거르지 않는다.
 * 응답에 증권 유형 필드가 없고({@code class}는 {@code us_equity} 하나) 이름 문자열의
 * 커버리지를 재본 적이 없어, 지금 거르면 <b>관측하지 못한 표기 위에 판정을 세우는 것</b>이다.
 * 잘못 분류된 ETF는 사업회사로 스크리닝에 올라오고 어떤 에러도 내지 않는다.
 */
@Component
@ConditionalOnApiKey("alpaca")
public class AlpacaUniverseAdapter implements UniverseDataSource {

    static final String SOURCE_NAME = "alpaca";

    private static final Logger log = LoggerFactory.getLogger(AlpacaUniverseAdapter.class);

    /** 이 유니버스 하나가 NYSE·NASDAQ을 함께 담는다. 둘로 가르면 백분위 모집단이 갈린다(ADR-0036). */
    public static final String UNIVERSE_CODE = "US_LISTED";

    /** {@code exchange}가 콤마 목록을 받는다는 것은 실측했다 — 그래서 실행당 1콜이다. */
    private static final String EXCHANGES = "NYSE,NASDAQ";

    /**
     * 이보다 적게 오면 부분 목록으로 본다.
     *
     * <p>2026-08-29 실측 8,504종목(NYSE 2,920 + NASDAQ 5,584)의 절반이다
     * ({@code AlpacaAssetProbeLiveTest}). <b>기획서의 추정치(~5,600)에서 뽑지 않았다</b> —
     * 추정치에서 뽑은 상수는 소스가 반쯤 준 날 울지 않는다. 착수 판단을 공표값의 절반으로
     * 한다는 {@code api-sources} skill의 규칙을 실측값에 적용한 것이다.
     */
    private static final int MIN_EXPECTED_ASSETS = 4_252;

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final SecurityNameCache nameCache;
    private final boolean credentialsPresent;
    private final int minExpectedAssets;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다
     * ({@code MarketCapUniverseAdapter}가 실측으로 남긴 함정이다).
     */
    @Autowired
    public AlpacaUniverseAdapter(ExternalApiClientFactory clientFactory,
                                 DataSourceProperties properties,
                                 @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter,
                                 SecurityNameCache nameCache) {
        this(clientFactory, properties, rateLimiter, nameCache,
                properties.getAlpaca().getBaseUrl(), MIN_EXPECTED_ASSETS);
    }

    /**
     * base URL과 하한을 바꿔 끼우는 생성자. MockWebServer 테스트 전용이다.
     *
     * <p>운영 하한이 4,252라 픽스처에 그만큼 넣지 않으면 <b>모든 시험이 부분 목록 거부로
     * 끝난다.</b> 규칙 자체는 하한 값과 무관하므로 작은 값으로 검증한다
     * ({@code MarketCapUniverseAdapter}가 상위 N에 같은 장치를 둔 이유와 같다).
     */
    AlpacaUniverseAdapter(ExternalApiClientFactory clientFactory,
                          DataSourceProperties properties,
                          RateLimiter rateLimiter,
                          SecurityNameCache nameCache,
                          String baseUrl,
                          int minExpectedAssets) {
        DataSourceProperties.Source source = properties.getAlpaca();
        this.client = clientFactory.create(source, baseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.nameCache = nameCache;
        this.credentialsPresent = source.hasApiKey() && source.hasSecretKey();
        this.minExpectedAssets = minExpectedAssets;
    }

    @Override
    public Set<String> supportedUniverseCodes() {
        return Set.of(UNIVERSE_CODE);
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public UniverseSnapshot fetchConstituents(String universeCode, LocalDate asOf)
            throws DataSourceException {
        if (!UNIVERSE_CODE.equals(universeCode)) {
            throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, SOURCE_NAME,
                    "담당하지 않는 유니버스다: " + universeCode);
        }
        requireCredentials();

        List<AlpacaAsset> assets = fetchActiveAssets();

        if (assets.size() < minExpectedAssets) {
            // MUST(ADR-0008): 부분 목록을 스냅샷으로 만들지 않는다. 리밸런싱이 "목록에 없으면
            // 제외"로 동작하므로 모자란 목록은 대량 오탈락이다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "활성 종목이 %d개뿐이다(하한 %d, 2026-08-29 실측 8,504의 절반) — 부분 목록을 반영하지 않는다."
                            .formatted(assets.size(), minExpectedAssets));
        }

        // 이름과 거래소를 캐시에 심는다. 미국에는 마스터 전용 조회가 사실상 여기뿐이라,
        // 이것이 없으면 StockRegistrar가 종목마다 단건 조회를 부르거나 이름을 티커로 채운다.
        List<String> tickers = new ArrayList<>(assets.size());
        Set<String> seen = new LinkedHashSet<>();
        int notTradable = 0;
        for (AlpacaAsset asset : assets) {
            if (!seen.add(asset.symbol())) {
                // 중복 심볼은 universe_membership의 EXCLUDE 제약에서 뒤늦게 터진다.
                // 실측에서는 0이었으므로, 나오면 소스가 바뀐 것이다.
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "중복 심볼이 왔다: " + asset.symbol());
            }
            nameCache.put(new SecurityDescriptor(
                    UsMarket.CODE, asset.symbol(), asset.name(),
                    null, null, null, null, asset.exchange()));
            tickers.add(asset.symbol());
            if (!asset.tradable()) {
                notTradable++;
            }
        }

        log.info("미국 상장 유니버스를 만들었다. {} 기준일={} 편입={}종목 (거래불가 {}종목 포함 — "
                        + "데이터 조회 전용이라 거르지 않는다)",
                universeCode, asOf, tickers.size(), notTradable);
        return new UniverseSnapshot(universeCode, asOf, tickers, SOURCE_NAME);
    }

    /**
     * NYSE·NASDAQ의 활성 미국 주식 전량. <b>페이징이 없어 한 응답에 전부 온다</b>(실측 3.88MB).
     *
     * <p>MUST: {@code status=active}를 명시한다. 기본값이 "모든 상태 포함"이라 빠뜨리면
     * 상장폐지 종목이 섞인다 — 실측에서 2,163개가 더 왔다.
     */
    private List<AlpacaAsset> fetchActiveAssets() throws DataSourceException {
        String context = "assets exchange=" + EXCHANGES;

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
                    .uri(uri -> uri.path("/v2/assets")
                            .queryParam("status", "active")
                            .queryParam("asset_class", "us_equity")
                            .queryParam("exchange", EXCHANGES)
                            .build())
                    .retrieve()
                    // MUST: 문자열로 받는다 — 오류 본문이 JSON이 아닌 엔드포인트가 있다(AlpacaResponse).
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }

        JsonNode array =
                AlpacaResponse.requireArray(response.getStatusCode(), response.getBody(), context);

        List<AlpacaAsset> assets = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            AlpacaAsset asset = AlpacaAsset.from(node);
            if (!asset.isUsable()) {
                // 파싱 실패를 건너뛰지 않는다 — 벤더 스펙 변경의 신호다.
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "필수 필드가 빈 자산이 왔다: " + node);
            }
            assets.add(asset);
        }
        return assets;
    }

    /**
     * 키가 둘 다 있어야 부른다.
     *
     * <p>{@code @ConditionalOnApiKey}는 {@code api-key} 하나만 보므로 <b>시크릿이 비어도
     * 어댑터는 등록된다.</b> 그대로 부르면 전 호출이 401이 되고, 그 401은 "키가 틀렸다"로
     * 읽혀 원인이 "한쪽만 채웠다"라는 것이 드러나지 않는다.
     */
    private void requireCredentials() throws DataSourceException {
        if (!credentialsPresent) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "APCA_API_KEY_ID와 APCA_API_SECRET_KEY가 둘 다 설정되어야 한다.");
        }
    }
}
