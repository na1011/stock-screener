package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityMasterDataSource;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
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

import java.util.Optional;
import java.util.Set;

/**
 * 미국 종목의 이름·거래소를 준다.
 *
 * <p><b>이 어댑터가 없으면 유니버스 적재가 조용히 망가진다.</b>
 * {@code StockRegistrar.describe()}는 처음 보는 티커마다
 * {@code registry.securityMasterSource(marketCode)}를 부르는데, {@code US} 담당이 없으면
 * {@code NOT_FOUND}가 나고 그 {@code catch}가 <b>이름을 티커로 대체한다.</b> 수천 종목이
 * {@code name = ticker}로 굳고 배치는 성공으로 끝나며, {@code resolveOne}이 기존 행의 이름을
 * 갱신하지 않으므로 그 값은 영구적이다.
 *
 * <p>유니버스 어댑터와 클래스를 나눈 것은 ADR-0006의 MUST다 — 한 벤더가 두 종류를 제공해도
 * 어댑터를 나눈다. <b>추가 호출은 0콜이다</b>: 같은 {@code /v2/assets} 응답이 이미 이름과
 * 거래소를 주므로 {@link SecurityNameCache}로 건네받는다.
 *
 * <p>캐시가 비었을 때만 단건 조회로 내려간다. 유니버스 리밸런싱은 목록 조회 직후에
 * {@code resolveOrCreate}를 부르므로 정상 경로에서는 늘 캐시가 찬다 — 단건 경로가 도는 것은
 * <b>손으로 티커를 준 경우</b>이고, 그때는 종목 수가 적다.
 */
@Component
@ConditionalOnApiKey("alpaca")
public class AlpacaSecurityMasterAdapter implements SecurityMasterDataSource {

    private static final String SOURCE_NAME = "alpaca:master";
    private static final Logger log = LoggerFactory.getLogger(AlpacaSecurityMasterAdapter.class);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final SecurityNameCache nameCache;

    @Autowired
    public AlpacaSecurityMasterAdapter(ExternalApiClientFactory clientFactory,
                                       DataSourceProperties properties,
                                       @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter,
                                       SecurityNameCache nameCache) {
        this(clientFactory, properties, rateLimiter, nameCache,
                properties.getAlpaca().getBaseUrl());
    }

    AlpacaSecurityMasterAdapter(ExternalApiClientFactory clientFactory,
                                DataSourceProperties properties,
                                RateLimiter rateLimiter,
                                SecurityNameCache nameCache,
                                String baseUrl) {
        DataSourceProperties.Source source = properties.getAlpaca();
        this.client = clientFactory.create(source, baseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.nameCache = nameCache;
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
    public Optional<SecurityDescriptor> findByTicker(String marketCode, String ticker)
            throws DataSourceException {
        Optional<SecurityDescriptor> cached = nameCache.find(marketCode, ticker);
        if (cached.isPresent()) {
            return cached;
        }
        log.debug("이름 캐시에 없어 단건 조회로 내려간다. {}/{}", marketCode, ticker);
        return fetchOne(marketCode, ticker);
    }

    private Optional<SecurityDescriptor> fetchOne(String marketCode, String ticker)
            throws DataSourceException {
        String context = "asset " + ticker;

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

        ResponseEntity<JsonNode> response;
        try {
            response = client.get().uri("/v2/assets/{symbol}", ticker)
                    .retrieve().toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }

        int status = response.getStatusCode().value();
        if (status == 404) {
            // 소스가 이 티커를 모른다. 예외가 아니다 — 포트 계약이 그렇게 정의한다.
            return Optional.empty();
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new DataSourceException(AlpacaResponse.kindOf(status), SOURCE_NAME,
                    "HTTP %d. %s".formatted(status, context));
        }

        if (response.getBody() == null) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "200인데 본문이 없다 — 스펙이 바뀌었을 수 있다. " + context);
        }
        AlpacaAsset asset = AlpacaAsset.from(response.getBody());
        if (!asset.isUsable()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "필수 필드가 빈 자산이 왔다. " + context);
        }
        return Optional.of(new SecurityDescriptor(
                marketCode, asset.symbol(), asset.name(),
                null, null, null, null, asset.exchange()));
    }
}
