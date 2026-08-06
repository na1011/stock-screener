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

    /**
     * 한 페이지에 요청할 행 수.
     *
     * <p>실측상 2,000까지 받아준다(6.5년 1,618행이 1콜). 상한이 문서화되어 있지 않아 벤더가
     * 언제든 조일 수 있으므로 <b>이 값에 기대지 않고 페이징을 함께 구현한다</b> — 조여지는
     * 순간 다시 조용히 잘리기 때문이다. 이 상수는 콜 수를 줄이는 최적화일 뿐이고,
     * 정확성의 근거는 {@code totalCount} 기반 루프다.
     */
    private static final int PAGE_SIZE = 2000;

    /**
     * 페이지 수 상한. 정상적으로는 도달하지 않는다.
     *
     * <p>{@code totalCount}가 실제 응답과 어긋나면(벤더 버그·스펙 변경) 종료 조건이 영영
     * 참이 되지 않는다. 배치가 무한히 도는 것보다 실패하는 편이 낫다.
     */
    private static final int MAX_PAGES = 50;

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

        List<RawPriceBar> bars = new ArrayList<>();
        int received = 0;
        int totalCount = Integer.MAX_VALUE;

        for (int pageNo = 1; received < totalCount; pageNo++) {
            if (pageNo > MAX_PAGES) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                        DataGoKrResponse.SOURCE_NAME,
                        "페이지 수가 상한을 넘었다(" + MAX_PAGES + "). totalCount=" + totalCount
                                + " 수신=" + received + " — 응답의 totalCount가 실제와 어긋난다. " + context);
            }

            final int page = pageNo;
            JsonNode body = callBody(uri -> uri
                    .path("/getStockPriceInfo")
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("resultType", "json")
                    .queryParam("numOfRows", PAGE_SIZE)
                    .queryParam("pageNo", page)
                    .queryParam("likeSrtnCd", security.ticker())
                    .queryParam("beginBasDt", from.format(BAS_DT))
                    // endBasDt는 "미만"이므로 to를 포함시키려면 하루 더한다.
                    .queryParam("endBasDt", to.plusDays(1).format(BAS_DT))
                    .build(), context + " page=" + page);

            totalCount = body.path("totalCount").asInt(0);
            JsonNode items = body.path("items").path("item");

            int itemsInPage = 0;
            for (JsonNode item : items) {
                itemsInPage++;
                // likeSrtnCd는 부분 일치라 다른 종목이 섞일 수 있다. 정확히 일치하는 것만 취한다.
                if (!security.ticker().equals(item.path("srtnCd").asText())) {
                    continue;
                }
                bars.add(toBar(item, context));
            }

            // IMPORTANT — 진행도는 필터 전 행 수로 센다. 걸러낸 뒤의 수로 세면 다른 종목이
            // 섞인 페이지에서 진행이 멈춘 것처럼 보여 같은 페이지를 무한히 다시 읽는다.
            received += itemsInPage;

            if (itemsInPage == 0) {
                // totalCount는 남았다는데 빈 페이지가 왔다. 더 돌아도 진전이 없다.
                if (received < totalCount) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                            DataGoKrResponse.SOURCE_NAME,
                            "totalCount=" + totalCount + "인데 " + received
                                    + "행에서 빈 페이지가 왔다 — 부분 결과를 반환하지 않는다. " + context);
                }
                break;
            }
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
                    // 상장주식수. PER·PBR의 분모이며, 날짜별 값이라 시세와 함께 와야 맞다.
                    // 우선주는 별도 종목으로 세므로 이것이 보통주 수다.
                    decimal(item, "lstgStCnt"),
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
     * 공통 호출. 레이트리밋을 적용하고 봉투를 판별한 뒤 {@code response.body}를 돌려준다.
     *
     * <p>항목이 아니라 body를 주는 이유는 {@code totalCount}가 페이징의 유일한 근거이기
     * 때문이다. 페이지마다 이 메서드를 부르므로 permit도 페이지마다 쓴다 — 중간에 쿼터가
     * 마르면 {@code RATE_LIMITED}가 나가고 <b>부분 결과는 반환되지 않는다.</b>
     */
    private JsonNode callBody(java.util.function.Function<org.springframework.web.util.UriBuilder,
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
        return DataGoKrResponse.requireBody(response.getStatusCode(), response.getBody(), context);
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
