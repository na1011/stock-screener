package com.stockscreener.pipeline.datasource.kr;

import tools.jackson.databind.JsonNode;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityMasterDataSource;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 종목 마스터를 시세 API에서 얻는다.
 *
 * <p>별도 마스터 API를 쓰지 않는 이유는, 시세 응답에 이미 {@code itmsNm}(종목명)과
 * {@code mrktCtg}(시장구분)가 들어 있기 때문이다. 새 편입 종목의 {@code stock} 행을 만드는 데
 * 필요한 것이 그 둘이라 추가 소스를 붙일 이유가 없다.
 *
 * <p>다만 <b>섹터와 상장일은 제공되지 않는다.</b> 그 두 컬럼은 nullable이고 v1 스크리닝이
 * 쓰지 않으므로 비워 둔다 — 채우려고 다른 소스를 붙이면 그 소스가 또 하나의 고장 지점이 된다.
 *
 * <p>시세 어댑터와 클래스를 나눈 이유는 갱신 주기가 다르기 때문이다. 마스터는 유니버스가
 * 바뀔 때만 필요하고, 합치면 매일 쓰지 않는 호출이 일일 배치의 쿼터를 태운다.
 */
// 키가 설정되지 않으면 빈으로 등록하지 않는다. 두 가지를 동시에 얻는다 —
// 키 없는 환경에서 애플리케이션이 정상 기동하고, 테스트가 Fake 소스를 쓸 때
// 같은 시장에 두 소스가 등록되는 충돌이 생기지 않는다.
@ConditionalOnApiKey("data-go-kr")
@Component
public class DataGoKrSecurityMasterAdapter implements SecurityMasterDataSource {

    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern TICKER = Pattern.compile("\\d{6}");

    /** 최근 어느 거래일이든 한 행이면 이름·시장을 알 수 있다. 넉넉히 거슬러 올라간다. */
    private static final int LOOKBACK_DAYS = 30;

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final String serviceKey;
    private final SecurityNameCache nameCache;

    public DataGoKrSecurityMasterAdapter(ExternalApiClientFactory clientFactory,
                                         DataSourceProperties properties,
                                         @Qualifier("dataGoKrRateLimiter") RateLimiter rateLimiter,
                                         SecurityNameCache nameCache) {
        DataSourceProperties.Source source = properties.getDataGoKr();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.serviceKey = ExternalApiClientFactory.normalizeServiceKey(source.getApiKey());
        this.nameCache = nameCache;
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of("KOSPI", "KOSDAQ");
    }

    @Override
    public String sourceName() {
        return DataGoKrResponse.SOURCE_NAME;
    }

    @Override
    public Optional<SecurityDescriptor> findByTicker(String marketCode, String ticker)
            throws DataSourceException {
        if (serviceKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH,
                    DataGoKrResponse.SOURCE_NAME, "DATA_GO_KR_SERVICE_KEY가 설정되지 않았다.");
        }
        if (!TICKER.matcher(ticker).matches()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DataGoKrResponse.SOURCE_NAME, "국내 티커는 6자리 숫자여야 한다: '" + ticker + "'");
        }

        // 유니버스 조회가 방금 받아온 전종목 응답에 이미 이름과 시장이 들어 있다.
        // 캐시가 없으면 350종목 등록에 350콜이 같은 정보를 다시 받으러 나간다.
        // 검증 뒤에 보는 이유는 잘못된 티커가 캐시를 우회해 조용히 통과하지 않게 하기 위해서다.
        Optional<SecurityDescriptor> cached = nameCache.find(marketCode, ticker);
        if (cached.isPresent()) {
            return cached;
        }

        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED,
                    DataGoKrResponse.SOURCE_NAME, "일일 호출 한도를 소진했다. ticker=" + ticker);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DataGoKrResponse.SOURCE_NAME, "호출 대기 중 인터럽트됐다. ticker=" + ticker, e);
        }

        LocalDate today = LocalDate.now();
        String context = "master ticker=" + ticker;
        ResponseEntity<JsonNode> response;
        try {
            response = client.get().uri(uri -> uri
                            .path("/getStockPriceInfo")
                            .queryParam("serviceKey", serviceKey)
                            .queryParam("resultType", "json")
                            .queryParam("numOfRows", 1)
                            .queryParam("pageNo", 1)
                            .queryParam("likeSrtnCd", ticker)
                            .queryParam("beginBasDt", today.minusDays(LOOKBACK_DAYS).format(BAS_DT))
                            .build())
                    .retrieve()
                    .toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DataGoKrResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }

        JsonNode items =
                DataGoKrResponse.requireItems(response.getStatusCode(), response.getBody(), context);
        for (JsonNode item : items) {
            if (!ticker.equals(item.path("srtnCd").asText())) {
                continue;
            }
            String name = item.path("itmsNm").asText("");
            String market = item.path("mrktCtg").asText(marketCode);
            if (name.isBlank()) {
                continue;
            }
            // 섹터·상장일은 이 소스가 주지 않는다. nullable이며 v1은 쓰지 않는다.
            return Optional.of(new SecurityDescriptor(
                    market, ticker, name, null, null, null, null));
        }
        // 빈 결과는 예외가 아니다 — 상장폐지·거래정지가 정상적으로 발생한다.
        return Optional.empty();
    }
}
