package com.stockscreener.pipeline.datasource.kr;

import tools.jackson.databind.JsonNode;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.PriceDataSource;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 공공데이터포털 주식시세정보 어댑터. 코스피·코스닥 일별 시세를 받는다.
 *
 * <p>스펙과 실측 결과는 {@code docs/api-spec/data-go-kr-stock-price.md}에 있다.
 *
 * <p>종목별 기간조회가 가능해 <b>한 종목 = 한 콜</b>이다(실측 확인). 일자별 전종목 조회만
 * 가능했다면 어댑터 구조가 완전히 달랐을 것이다.
 */
// 키가 설정되지 않으면 빈으로 등록하지 않는다. 두 가지를 동시에 얻는다 —
// 키 없는 환경에서 애플리케이션이 정상 기동하고, 테스트가 Fake 소스를 쓸 때
// 같은 시장에 두 소스가 등록되는 충돌이 생기지 않는다.
@ConditionalOnApiKey("data-go-kr")
@Component
public class DataGoKrPriceAdapter implements PriceDataSource {

    private static final Logger log = LoggerFactory.getLogger(DataGoKrPriceAdapter.class);

    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern TICKER = Pattern.compile("\\d{6}");

    /** 2년치가 약 500행이므로 한 번에 받는다. 페이징이 필요하면 로그로 드러난다. */
    private static final int PAGE_SIZE = 1000;

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final String serviceKey;

    public DataGoKrPriceAdapter(ExternalApiClientFactory clientFactory,
                                DataSourceProperties properties,
                                @Qualifier("dataGoKrRateLimiter") RateLimiter rateLimiter) {
        DataSourceProperties.Source source = properties.getDataGoKr();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.serviceKey = ExternalApiClientFactory.normalizeServiceKey(source.getApiKey());
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
    public List<RawPriceBar> fetchDailyBars(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        requireKey();
        // MUST — 요청 전 검증. 잘못된 파라미터가 "빈 결과"와 응답상 구별되지 않으므로
        // 여기서 막지 않으면 날짜 형식 오타가 "그날 데이터 없음"으로 조용히 저장된다.
        // 이 프로젝트에서 가장 발견하기 어려운 종류의 결함이다.
        validateRequest(security, from, to);

        String context = "ticker=" + security.ticker() + " " + from + "~" + to;
        JsonNode items = call(uri -> uri
                .path("/getStockPriceInfo")
                .queryParam("serviceKey", serviceKey)
                .queryParam("resultType", "json")
                .queryParam("numOfRows", PAGE_SIZE)
                .queryParam("pageNo", 1)
                .queryParam("likeSrtnCd", security.ticker())
                .queryParam("beginBasDt", from.format(BAS_DT))
                // endBasDt는 "미만"이므로 to를 포함시키려면 하루 더한다.
                .queryParam("endBasDt", to.plusDays(1).format(BAS_DT))
                .build(), context);

        List<RawPriceBar> bars = new ArrayList<>();
        for (JsonNode item : items) {
            // likeSrtnCd는 부분 일치라 다른 종목이 섞일 수 있다. 정확히 일치하는 것만 취한다.
            if (!security.ticker().equals(item.path("srtnCd").asText())) {
                continue;
            }
            bars.add(toBar(item, context));
        }
        // 포트 계약: 날짜 오름차순
        bars.sort(Comparator.comparing(RawPriceBar::tradeDate));
        return bars;
    }

    private RawPriceBar toBar(JsonNode item, String context) throws DataSourceException {
        try {
            return new RawPriceBar(
                    LocalDate.parse(item.path("basDt").asText(), BAS_DT),
                    decimal(item, "mkp"),
                    decimal(item, "hipr"),
                    decimal(item, "lopr"),
                    decimal(item, "clpr"),
                    Long.parseLong(item.path("trqu").asText("0")),
                    // KRX는 거래대금을 실제로 제공한다 — 합성이 아니라 실측값이다.
                    decimal(item, "trPrc"),
                    // 원주가를 준다. 수정주가는 우리가 adj_factor로 만든다.
                    false);
        } catch (RuntimeException e) {
            // 파싱 실패는 벤더 스펙 변경의 신호다. 조용히 건너뛰지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DataGoKrResponse.SOURCE_NAME,
                    "시세 항목을 해석할 수 없다. " + context + " item=" + item, e);
        }
    }

    private static BigDecimal decimal(JsonNode item, String field) {
        String raw = item.path(field).asText("");
        if (raw.isBlank() || "-".equals(raw)) {
            return null;
        }
        // 값에 콤마가 들어오는 사례가 보고되어 방어한다(실측에서는 없었다).
        return new BigDecimal(raw.replace(",", ""));
    }

    /**
     * 공통 호출. 레이트리밋을 적용하고 봉투를 판별한다.
     */
    private JsonNode call(java.util.function.Function<org.springframework.web.util.UriBuilder,
            java.net.URI> uriSpec, String context) throws DataSourceException {
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED,
                    DataGoKrResponse.SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            // MUST: 인터럽트 상태를 복원한다. 삼키면 배치 중단 신호가 사라진다.
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DataGoKrResponse.SOURCE_NAME, "호출 대기 중 인터럽트됐다. " + context, e);
        }

        ResponseEntity<JsonNode> response;
        try {
            response = client.get().uri(uriSpec).retrieve().toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            // 타임아웃·연결 실패. 벤더 예외가 밖으로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DataGoKrResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }
        return DataGoKrResponse.requireItems(response.getStatusCode(), response.getBody(), context);
    }

    private void requireKey() throws DataSourceException {
        if (serviceKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH,
                    DataGoKrResponse.SOURCE_NAME,
                    "DATA_GO_KR_SERVICE_KEY가 설정되지 않았다.");
        }
    }

    /**
     * 요청 파라미터를 미리 검증한다.
     *
     * <p>IMPORTANT — 이것이 silent fail을 막는 유일한 수단이다. 실측 결과 없는 종목·휴장일·
     * <b>잘못된 파라미터</b>가 모두 {@code resultCode "00"} + {@code totalCount 0}으로
     * 동일하게 응답한다. 응답만 보고는 구별할 수 없으므로 보내기 전에 막는다.
     */
    private void validateRequest(SecurityRef security, LocalDate from, LocalDate to)
            throws DataSourceException {
        if (!TICKER.matcher(security.ticker()).matches()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DataGoKrResponse.SOURCE_NAME,
                    "국내 티커는 6자리 숫자여야 한다: '" + security.ticker() + "'");
        }
        if (from.isAfter(to)) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DataGoKrResponse.SOURCE_NAME, "조회 구간이 뒤집혔다: " + from + " ~ " + to);
        }
    }

    /** 남은 쿼터를 로그로 남긴다. 배치가 소진 임박을 인지할 수 있어야 한다. */
    public long usedToday() {
        return rateLimiter.usedToday();
    }
}
