package com.stockscreener.pipeline.datasource.kr;

import tools.jackson.databind.JsonNode;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.MarketIndexDataSource;
import com.stockscreener.pipeline.datasource.RawIndexBar;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

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
import java.util.Map;
import java.util.Set;

/**
 * 공공데이터포털 지수시세정보 어댑터. 코스피·코스닥 지수의 일별 시세를 받는다.
 *
 * <p>스펙과 실측 결과는 {@code docs/api-spec/data-go-kr-market-index.md}에 있다.
 * 시세정보와 <b>같은 제공기관(prefix 1160100)</b>이지만 활용신청은 별개이고 경로가 다르다.
 *
 * <p>지수명 + 기간으로 <b>한 지수 = 한 콜</b>이다(실측: 482행이 1콜). 시세 어댑터와 같은 모양이다.
 */
// 시세와 같은 발급 키를 쓴다. 별도 키 설정을 만들지 않는 이유는 DataSourceProperties에 있다.
@ConditionalOnApiKey("data-go-kr")
@Component
public class DataGoKrMarketIndexAdapter implements MarketIndexDataSource {

    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 우리 지수 코드 → 소스의 {@code idxNm} <b>정확 일치</b> 문자열.
     *
     * <p>IMPORTANT — 이 문자열이 틀리면 <b>0건이 오고, 그것은 "그날 데이터 없음"과 응답상
     * 구별되지 않는다.</b> 부분일치({@code likeIdxNm})를 쓰지 않는 이유가 그것이다 —
     * {@code 코스피}로 부분일치하면 {@code 코스피 200}·{@code 코스피 건설업}까지 섞여 온다.
     *
     * <p>숫자를 붙여 쓰지 않는다({@code 코스피200}이 아니라 {@code 코스피 200}). 지금 여기
     * 담긴 둘에는 숫자가 없지만, 늘릴 때 가장 먼저 밟는 함정이라 적어 둔다.
     *
     * <p>벤더 문자열이 어댑터에 있는 것이 포트-어댑터의 요점이다(ADR-0006). 조회표 테이블을
     * 만들지 않는 이유이기도 하다 — 사본이 늘면 갈린다.
     */
    private static final Map<String, String> INDEX_NAMES = Map.of(
            "KOSPI", "코스피",
            "KOSDAQ", "코스닥");

    /**
     * 한 페이지에 요청할 행 수.
     *
     * <p>실측상 시세와 같은 상한을 받아준다. 문서의 "최대 메시지 4000 byte"는 실제 상한이
     * 아니다. 그래도 {@code totalCount} 기반 페이징을 함께 두는 이유는 시세 어댑터와 같다 —
     * 벤더가 조이는 순간 <b>경고 없이 잘리기</b> 때문이다.
     */
    private static final int PAGE_SIZE = 2000;

    /** 페이지 수 상한. {@code totalCount}가 실제와 어긋날 때 무한 루프 대신 실패한다. */
    private static final int MAX_PAGES = 20;

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final String serviceKey;

    public DataGoKrMarketIndexAdapter(ExternalApiClientFactory clientFactory,
                                      DataSourceProperties properties,
                                      @Qualifier("dataGoKrRateLimiter") RateLimiter rateLimiter) {
        DataSourceProperties.Source source = properties.getDataGoKr();
        this.client = clientFactory.create(source, properties.getDataGoKrMarketIndexBaseUrl());
        this.rateLimiter = rateLimiter;
        this.serviceKey = ExternalApiClientFactory.normalizeServiceKey(source.getApiKey());
    }

    @Override
    public Set<String> supportedIndexCodes() {
        return INDEX_NAMES.keySet();
    }

    @Override
    public String sourceName() {
        return DataGoKrResponse.SOURCE_NAME;
    }

    @Override
    public List<RawIndexBar> fetchDailyBars(String indexCode, LocalDate from, LocalDate to)
            throws DataSourceException {
        requireKey();
        // MUST — 요청 전 검증. 잘못된 파라미터가 "빈 결과"와 응답상 구별되지 않으므로 여기서
        // 막지 않으면 오타가 "그 구간 데이터 없음"으로 조용히 저장된다(시세와 같은 함정이다).
        String indexName = INDEX_NAMES.get(indexCode);
        if (indexName == null) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DataGoKrResponse.SOURCE_NAME,
                    "담당하지 않는 지수 코드다: '" + indexCode + "' — 아는 코드: " + INDEX_NAMES.keySet());
        }
        if (from.isAfter(to)) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DataGoKrResponse.SOURCE_NAME, "조회 구간이 뒤집혔다: " + from + " ~ " + to);
        }

        String context = "index=" + indexCode + "(" + indexName + ") " + from + "~" + to;

        List<RawIndexBar> bars = new ArrayList<>();
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
                    .path("/getStockMarketIndex")
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("resultType", "json")
                    .queryParam("numOfRows", PAGE_SIZE)
                    .queryParam("pageNo", page)
                    .queryParam("idxNm", indexName)
                    .queryParam("beginBasDt", from.format(BAS_DT))
                    // IMPORTANT — endBasDt는 "미만"이다(문서 원문: 검색값보다 작은 데이터).
                    // 하루를 더하지 않으면 구간 끝 하루가 조용히 빠진다.
                    .queryParam("endBasDt", to.plusDays(1).format(BAS_DT))
                    .build(), context + " page=" + page);

            totalCount = body.path("totalCount").asInt(0);
            JsonNode items = body.path("items").path("item");

            int itemsInPage = 0;
            for (JsonNode item : items) {
                itemsInPage++;
                // idxNm은 일치 조회지만 응답을 그대로 믿지 않는다. 다른 지수가 섞여 오면
                // 그 행이 우리 코드의 시계열이 되어 26주 수익률이 통째로 다른 값이 된다.
                if (!indexName.equals(item.path("idxNm").asText())) {
                    continue;
                }
                bars.add(toBar(item, context));
            }

            // IMPORTANT — 진행도는 필터 전 행 수로 센다. 걸러낸 뒤의 수로 세면 진행이 멈춘 것처럼
            // 보여 같은 페이지를 무한히 다시 읽는다(시세 어댑터와 같은 자리다).
            received += itemsInPage;

            if (itemsInPage == 0) {
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
        bars.sort(Comparator.comparing(RawIndexBar::tradeDate));
        return bars;
    }

    private RawIndexBar toBar(JsonNode item, String context) throws DataSourceException {
        try {
            return new RawIndexBar(
                    LocalDate.parse(item.path("basDt").asText(), BAS_DT),
                    decimal(item, "mkp"),
                    decimal(item, "hipr"),
                    decimal(item, "lopr"),
                    decimal(item, "clpr"),
                    longOrNull(item, "trqu"),
                    decimal(item, "trPrc"),
                    intOrNull(item, "epyItmsCnt"));
        } catch (RuntimeException e) {
            // 파싱 실패는 벤더 스펙 변경의 신호다. 조용히 건너뛰지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    DataGoKrResponse.SOURCE_NAME,
                    "지수 항목을 해석할 수 없다. " + context + " item=" + item, e);
        }
    }

    private static BigDecimal decimal(JsonNode item, String field) {
        String raw = text(item, field);
        return raw == null ? null : new BigDecimal(raw);
    }

    private static Long longOrNull(JsonNode item, String field) {
        String raw = text(item, field);
        return raw == null ? null : Long.valueOf(new BigDecimal(raw).longValue());
    }

    private static Integer intOrNull(JsonNode item, String field) {
        String raw = text(item, field);
        return raw == null ? null : Integer.valueOf(new BigDecimal(raw).intValue());
    }

    /** 값에 콤마가 들어오는 사례가 보고되어 방어한다(시세와 같다). */
    private static String text(JsonNode item, String field) {
        String raw = item.path(field).asText("");
        if (raw.isBlank() || "-".equals(raw)) {
            return null;
        }
        return raw.replace(",", "");
    }

    /**
     * 공통 호출. 레이트리밋을 적용하고 봉투를 판별한 뒤 {@code response.body}를 돌려준다.
     *
     * <p>{@code totalCount}가 페이징의 유일한 근거이므로 항목이 아니라 body를 준다.
     * 승인 전에는 이 서비스가 <b>HTTP 403 + {@code OpenAPI_ServiceResponse} 봉투</b>로 답하는데
     * ({@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}), 그 판별은 {@link DataGoKrResponse}가 이미
     * 한다 — 같은 키로 시세는 정상이므로 <b>키가 틀린 것으로 읽지 않는다.</b>
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
}
