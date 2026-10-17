package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.GovernanceDataSource;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.pipeline.datasource.GovernanceReportRow;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 미국 현금배당. Alpaca Market Data API의 {@code /v1/corporate-actions}에서
 * <b>{@code cash_dividends}만</b> 받아 거버넌스 원문으로 담는다.
 *
 * <p>{@link AlpacaCorporateActionAdapter}와 같은 엔드포인트를 부르면서 <b>어댑터를 나눈 이유</b>가
 * ADR-0006의 MUST다 — 한 벤더가 여러 종류를 제공해도 포트마다 어댑터를 나눈다. 그쪽은
 * {@code CorporateActionDataSource}(수정계수)이고 이쪽은 {@link GovernanceDataSource}(사실)라
 * <b>반환 타입도 저장 표도 다르다.</b> 대가는 종목당 호출 한 번이 겹치는 것이고, 그 수를
 * ADR에 적었다.
 *
 * <h2>여덟 중 하나만 답한다</h2>
 * <p>{@link #supportedEndpoints()}가 {@link GovernanceEndpoint#DIVIDEND} 하나다. 잡이 그것을 안
 * 보고 여덟을 다 부르면 나머지 일곱에 <b>「그 회사에 그 표가 없다」는 거짓 행</b>이 쌓인다 —
 * 미국에 없는 것은 그 회사의 표가 아니라 <b>그 표를 주는 소스</b>다.
 *
 * <h2>MUST: 요청 창과 필터 기준이 다르다</h2>
 * <p>{@code start}/{@code end}는 <b>{@code process_date}</b> 기준인데 우리가 자르는 것은
 * <b>{@code ex_date}</b>다. 실측으로 배당의 처리 지연은 <b>최대 50일</b>이고 음수가 없다
 * ({@code alpaca-corporate-action.md}). 그래서 요청은 넓게 하고 {@code ex_date}로 거른다.
 *
 * <h2>MUST: 페이지를 끝까지 돈다</h2>
 * <p>정렬이 심볼 우선이라 중간에 멈추면 <b>뒤쪽 심볼이 통째로 빈다.</b> 종목 하나씩 부르므로
 * 지금은 그 위험이 작지만, 상한에 닿으면 <b>부분 결과를 버리고 던진다</b> — 잘린 목록은
 * 「그 기간에 배당이 없다」와 구별되지 않는다.
 *
 * <h2>사건이 0건인 해도 행을 남긴다</h2>
 * <p>{@link GovernanceReportRow#noData}에 <b>접수번호를 찍어서</b> 낸다. 국내용 스탬프 경로
 * ({@code GovernanceRawWriter.stampNoData})와 미스탬프 집계가 둘 다 {@code receipt_no = ''}로
 * 대상을 잡으므로, 값을 주면 그 둘이 <b>미국 행을 건드리지도 세지도 않는다.</b> 안 주면 잡이
 * <i>"그 종목의 그 해는 여덟이 다 비었다"</i>는 <b>거짓 문장</b>을 로그한다 — 미국에 여덟은 없다.
 *
 * <p><b>MUST: 그 접수번호를 숫자로 시작하게 둔다.</b> 정정본 선택이 접수번호의 <b>문자열
 * 비교</b>라({@code DartGovernanceFactMapper.later}) {@code 'Y…'} 같은 값은 여덟 자리 날짜
 * 전부보다 크게 앉고, 그러면 <b>무배당 행이 실제 배당 행을 이긴다.</b>
 */
@Component
@ConditionalOnApiKey("alpaca")
public class AlpacaDividendAdapter implements GovernanceDataSource {

    static final String SOURCE_NAME = "alpaca:cash-dividend";

    /**
     * {@code governance_report_row.reprt_code}에 담는 값. DART의 보고서 코드가 아니라
     * <b>이 소스의 이름표</b>다 — {@code 11011}을 재사용하면 국내 보고서에서 온 행처럼 읽힌다.
     */
    static final String REPORT_CODE = "CASHDIV";

    /** 봉투 안의 배당 버킷 키. */
    private static final String BUCKET = "cash_dividends";

    /** 최대치. 종목 하나의 몇 해치는 한 페이지에 들어온다(실측 50종목 8년이 889건·1페이지). */
    private static final int PAGE_LIMIT = 1000;

    private static final int MAX_PAGES = 50;

    /**
     * {@code start}를 요청 연도보다 얼마나 앞당기는가. 창이 {@code process_date} 기준이라
     * <b>효력일보다 먼저 처리된 사건</b>을 놓치지 않으려는 것이다. 실측 지연은 음수가 0건이지만
     * 표본이 50종목·8년이라 <b>재본 범위 밖을 상수 하나로 덮는다.</b> 넓혀도 비용은 페이지 수뿐이다.
     */
    private static final int LEAD_DAYS = 400;

    /**
     * {@code end}를 요청 연도 끝보다 얼마나 미루는가. 실측 최대 처리 지연 50일에 마진을 얹었다.
     * <b>완결 판정은 여기서 하지 않는다</b> — 그것은 수집일과 회계연도 끝을 견주는 유도의 몫이다.
     */
    private static final int TRAIL_DAYS = 120;

    private static final Logger log = LoggerFactory.getLogger(AlpacaDividendAdapter.class);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean credentialsPresent;
    private final int maxPages;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지 못해
     * <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public AlpacaDividendAdapter(ExternalApiClientFactory clientFactory,
                                 DataSourceProperties properties,
                                 @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter, properties.getAlpacaMarketDataBaseUrl(),
                MAX_PAGES);
    }

    // MockWebServer 테스트 전용 — base URL과 페이지 상한을 바꿔 끼운다.
    AlpacaDividendAdapter(ExternalApiClientFactory clientFactory,
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
    public Set<GovernanceEndpoint> supportedEndpoints() {
        return EnumSet.of(GovernanceEndpoint.DIVIDEND);
    }

    /**
     * <b>올해를 포함한다.</b> 여기서 {@code bsnsYear}는 회계연도가 아니라 <b>{@code ex_date}의
     * 역년</b>이고({@code fetchReport}가 {@code [1/1, 12/31]} 창으로 묻는다) 배당은 그해에
     * 이미 지급되므로 <b>올해가 지금 답을 준다.</b>
     *
     * <p><b>기본값(작년)을 쓰면 비12월 결산 종목이 매년 영구히 한 해 뒤처진다.</b>
     * {@code AlpacaGovernanceFactMapper.coversWholeWindow}가 회계연도 창이 걸친 <b>모든 역년</b>을
     * 요구하는데, 52/53주 회계연도는 그 창이 이듬해 1월 초까지 뻗는다 —
     * {@code SNA}의 FY2025가 {@code 2024-12-29 ~ 2026-01-03}이라 역년 <b>2026</b>이 있어야
     * 값이 난다. 2026-09-20 실측: 그 역년 하나를 더하니 최신 회계연도가 나오는 종목이
     * <b>836 → 994</b>가 됐다.
     *
     * <p><b>같은 날 적은 「과거로 {@code span}을 넓히는 것은 +1종목」은 최신일에서 잰 수다.</b>
     * 보존 창 <b>앞부분</b>에서 다시 재면 다르다 — 창 시작에서 {@code figuresOn}이 고르는
     * 회계연도가 비12월 결산에서 역년 2023에 걸리기 때문이다. 커버가 {@code span} 3에 856,
     * <b>4에 970</b>, 5에도 970이다(2026-09-20 · 편입 1,000). 그래서 {@code YEARS_BACK}의
     * {@code DIVIDEND}가 4다. <b>최신일에서 잰 수로 창 전체를 판정하지 않는다</b> —
     * 위쪽 끝과 아래쪽 끝은 서로 다른 것을 막는다.
     */
    @Override
    public int latestRequestableYear(java.time.LocalDate asOf) {
        return asOf.getYear();
    }

    /**
     * 회계연도 하나가 역년 둘~셋에 걸치므로 <b>한 해가 더 필요하다.</b>
     *
     * <p>위쪽 끝이 최신 회계연도를 열었다면 이쪽은 <b>보존 창 앞부분</b>을 연다 —
     * 창 시작에서 {@code figuresOn}이 고르는 회계연도가 비12월 결산에서 역년 하나를 더 걸친다.
     * 실측으로 커버가 역년 셋에 856 · 넷에 <b>970</b> · 다섯에도 970이라(2026-09-20 · 편입 1,000)
     * <b>1이 정확한 값이고 2는 콜만 태운다.</b>
     */
    @Override
    public int extraCalendarYears() {
        return 1;
    }

    @Override
    public List<GovernanceReportRow> fetchReport(SecurityRef security, GovernanceEndpoint endpoint,
                                                 int bsnsYear, String reportCode)
            throws DataSourceException {
        requireCredentials();
        if (endpoint != GovernanceEndpoint.DIVIDEND) {
            // 던진다. 빈 목록이나 noData로 접으면 「이 소스는 그 표를 안 준다」가
            // 「그 회사에 그 표가 없다」로 저장되고, 그 둘을 가르는 것이 이 행의 존재 이유다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "이 소스가 답하지 않는 표다: " + endpoint + " — supportedEndpoints를 보라.");
        }

        LocalDate yearStart = LocalDate.of(bsnsYear, 1, 1);
        LocalDate yearEnd = LocalDate.of(bsnsYear, 12, 31);
        String symbol = security.ticker();
        List<JsonNode> inYear = new ArrayList<>();
        // 기호 시대마다 묻는다(ADR-0180) — 이 소스는 배당을 <b>그때의 기호</b> 아래 둔다(실측 ★15: FGNXP의 배당 5건이 개명 뒤 FGCPP로는
        // 안 온다). 시대는 지금 표지 + 저장된 기호 시대(신원 판정의 개명 갈래가 더한다)다. 개명 아닌 행은 저장 시대가 0줄이라 지금 표지
        // 하나를 경계 없이 묻는다 — 지금까지와 같다. 원장 name_changes 걷기(이 표가 생기기 전 개명 · FLG←NYCB)는 여기 없다 — 그것은
        // 개명 아닌 행의 값도 바꾸므로 관문 설정 뒤 몫이다.
        List<SecurityRef.SymbolEra> recorded = security.identity().recordedEras();
        LocalDate currentSince = recorded.stream().map(SecurityRef.SymbolEra::validTo).max(LocalDate::compareTo).orElse(null);
        List<SecurityRef.SymbolEra> eras = new ArrayList<>(recorded);
        for (SecurityRef.SymbolEra era : eras) {
            collectInYear(era.symbol(), era.validFrom(), era.validTo(), yearStart, yearEnd, inYear);
        }
        collectInYear(symbol, currentSince, null, yearStart, yearEnd, inYear);

        if (inYear.isEmpty()) {
            // 「그 해에 배당이 없었다」. 접수번호를 찍어 두면 국내용 스탬프 경로와 미스탬프
            // 집계가 이 행을 안 건드린다 — 클래스 javadoc의 마지막 절이 그 이유다.
            return List.of(GovernanceReportRow
                    .noData(GovernanceEndpoint.DIVIDEND, bsnsYear, REPORT_CODE)
                    .stampedWith(bsnsYear + "1231", null));
        }

        // 같은 ex_date에 둘 이상이 올 수 있어(정기 + 특별) 순번이 필요한데, 그 순번은
        // 재적재해도 같아야 한다 — uq_gov_row에 row_ordinal이 들어 있어 흔들리면 같은 배당이
        // 두 행이 되고 합이 부푼다. 그래서 응답 순서가 아니라 값으로 정렬해 번호를 매긴다.
        inYear.sort(Comparator
                .comparing((JsonNode node) -> node.path("ex_date").asString(""))
                .thenComparing(node -> node.path("rate").asString(""))
                .thenComparing(node -> node.path("special").asBoolean(false)));

        List<GovernanceReportRow> rows = new ArrayList<>(inYear.size());
        String previousExDate = null;
        int ordinal = 0;
        for (JsonNode event : inYear) {
            String exDate = event.path("ex_date").asString("");
            ordinal = exDate.equals(previousExDate) ? ordinal + 1 : 0;
            previousExDate = exDate;
            rows.add(new GovernanceReportRow(GovernanceEndpoint.DIVIDEND, bsnsYear, REPORT_CODE,
                    exDate.replace("-", ""), LocalDate.parse(exDate), ordinal, fieldsOf(event)));
        }
        return rows;
    }

    /**
     * 한 기호 시대의 그해 배당을 모은다. 시대 경계는 {@code ex_date}로 건다 — {@code since} 포함 · {@code until} 제외(둘 다 null이면 경계
     * 없음). 경계 밖은 그 기호를 다른 실체가 쓰던 때일 수 있다.
     */
    private void collectInYear(String symbol, LocalDate since, LocalDate until, LocalDate yearStart, LocalDate yearEnd,
                               List<JsonNode> inYear) throws DataSourceException {
        if ((until != null && !until.isAfter(yearStart)) || (since != null && since.isAfter(yearEnd))) {
            return; // 이 시대가 그해와 안 겹친다 — 콜을 태우지 않는다
        }
        for (JsonNode event : fetchAll(symbol, yearStart.minusDays(LEAD_DAYS), yearEnd.plusDays(TRAIL_DAYS))) {
            String rowSymbol = event.path("symbol").asString("");
            if (!symbol.equals(rowSymbol)) {
                // 우리가 안 물어본 종목이다. 붙이면 엉뚱한 종목의 배당이 아무 에러 없이 들어간다.
                log.warn("요청한 종목과 다른 심볼의 배당이라 건너뛴다. 요청={} 응답={}",
                        symbol, rowSymbol.isBlank() ? "(없음)" : rowSymbol);
                continue;
            }
            LocalDate exDate = dateOf(event, "ex_date");
            if (exDate == null) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "ex_date가 없거나 날짜가 아니다. ticker=" + symbol + " 행=" + event);
            }
            if ((since != null && exDate.isBefore(since)) || (until != null && !exDate.isBefore(until))) {
                continue;
            }
            if (!exDate.isBefore(yearStart) && !exDate.isAfter(yearEnd)) {
                inYear.add(event);
            }
        }
    }

    /** 페이지를 끝까지 돈다. 부분 결과는 「배당이 없다」와 구별되지 않으므로 반환하지 않는다. */
    private List<JsonNode> fetchAll(String symbol, LocalDate start, LocalDate end)
            throws DataSourceException {
        List<JsonNode> events = new ArrayList<>();
        String pageToken = null;
        int page = 0;
        do {
            String context = "cash-dividends %s %s~%s page=%d"
                    .formatted(symbol, start, end, page + 1);
            ResponseEntity<String> response = call(symbol, start, end, pageToken, context);
            AlpacaResponse.Envelope envelope = AlpacaResponse.requireCorporateActions(
                    response.getStatusCode(), response.getBody(), SOURCE_NAME, context);

            JsonNode bucket = envelope.value().path(BUCKET);
            if (!bucket.isMissingNode() && !bucket.isNull()) {
                if (!bucket.isArray()) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                            "'" + BUCKET + "'의 값이 배열이 아니다. " + context);
                }
                bucket.forEach(events::add);
            }

            pageToken = envelope.nextPageToken();
            page++;
            if (page >= maxPages && pageToken != null) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "페이지가 %d를 넘는다 — 상한을 걸고 부분 결과를 버린다. %s"
                                .formatted(maxPages, context));
            }
        } while (pageToken != null);
        return events;
    }

    /**
     * 응답 행의 필드 전부를 <b>문자열 그대로</b> 옮긴다. 해석은 매퍼의 몫이다(ADR-0081) —
     * 여기서 타입을 확정하면 매핑을 한 번 틀릴 때마다 수천 콜을 다시 태워야 한다.
     */
    private static Map<String, String> fieldsOf(JsonNode event) {
        Map<String, String> fields = new LinkedHashMap<>();
        event.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isNull() && !value.isMissingNode()) {
                fields.put(entry.getKey(), value.asString());
            }
        });
        return fields;
    }

    private static LocalDate dateOf(JsonNode node, String field) {
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
                                // 여기서는 types로 좁힌다. 분할·병합 어댑터가 이미 안 좁혀 부르며
                                // 「담지 않은 것」의 분포를 세고 있어(P23 축③) 두 번 셀 이유가 없다.
                                .queryParam("types", "cash_dividend")
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
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }
    }

    private void requireCredentials() throws DataSourceException {
        if (!credentialsPresent) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "APCA_API_KEY_ID와 APCA_API_SECRET_KEY가 둘 다 설정되어야 한다.");
        }
    }
}
