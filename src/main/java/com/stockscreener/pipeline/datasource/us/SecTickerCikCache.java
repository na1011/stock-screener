package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 티커 → CIK 매핑. <b>이 엔드포인트 계약의 정본이 여기다</b> — 해석 지점이 저장소에 이
 * 클래스 하나뿐이라 문서로 떼면 갈려도 아무도 모른다({@code DartCorpCodeCache}와 같은 취급).
 *
 * <p><b>계약</b>({@code GET https://www.sec.gov/files/company_tickers_exchange.json}):
 * 요청 인자가 없고 {@code User-Agent}만 필요하다. 응답은
 * {@code {"fields":["cik","name","ticker","exchange"], "data":[[320193,"Apple Inc.","AAPL","Nasdaq"], …]}}
 * 형태의 <b>열 배열</b>이다 — 객체가 아니라 <b>위치로 실린다.</b>
 * 실측 <b>521,231바이트 · 10,391행 · 서로 다른 CIK 8,004</b>(2026-08-29).
 *
 * <p><b>MUST: 필드 위치를 {@code fields}에서 푼다.</b> 인덱스를 상수로 박으면 소스가 열 순서를
 * 바꾸는 날 <b>회사명이 티커 자리에 들어오고</b> 아무 에러도 나지 않는다.
 *
 * <p><b>MUST: CIK를 10자리 zero-padding해 URL을 만든다.</b> 개발가이드가 404의 최다 원인으로
 * 적었고 실측에서도 {@code CIK320193.json}이 404였다. {@link #padded(long)}가 그 자리다.
 *
 * <p><b>정규화는 점을 하이픈으로 바꾸는 것 하나다.</b> 시세 소스는 주식 클래스를 {@code BRK.A}로,
 * SEC는 {@code BRK-A}로 싣는다(우리 유니버스에서 24종목).
 *
 * <p><b>MUST NOT: 우선주를 {@code .PR<X>} → {@code -P<X>}로 붙이지 않는다.</b> 그렇게 하면
 * <b>365종목이 더 붙지만 그 CIK가 보통주와 같아</b> 발행사 재무가 우선주 행에 실린다.
 * 우선주는 그 재무로 PER·PBR을 매길 대상이 아니고, 담을지는 노출 정책이 정할 문제다(ADR-0089).
 * 이 결정을 뒤집으려면 그 수를 먼저 다시 잰다 — {@code SecEdgarProbeLiveTest}가 찍는다.
 *
 * <p><b>안 붙는 티커는 예외가 아니라 {@link Optional#empty()}다.</b> 우리 8,504 중 1,815가
 * 이 파일에 없고 대부분 ETF·펀드(1,332)와 우선주·워런트·유닛(483)이다 — <b>정상이다.</b>
 * 그것을 예외로 올리면 배치가 매 실행 1,815건의 실패를 남기고 진짜 장애가 묻힌다.
 *
 * <p><b>인메모리 + 지연 로딩이다.</b> 기동 시에 받으면 신원 표시가 없는 개발 환경에서 앱이
 * 안 뜬다({@code DartCorpCodeCache}와 같은 이유). {@code @ConditionalOnApiKey}를 걸지 않는
 * 것도 같다 — 없으면 조회 시점에 {@code AUTH}로 실패하는 편이 조용한 미등록보다 낫다.
 */
@Component
public class SecTickerCikCache {

    private static final Logger log = LoggerFactory.getLogger(SecTickerCikCache.class);
    private static final String PATH = "/files/company_tickers_exchange.json";

    /** 실측 10,391행의 절반. 소스가 반쯤 준 날 울게 하려는 하한이다(2026-08-29 측정). */
    private static final int MIN_EXPECTED_ROWS = 5_195;

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean userAgentPresent;
    private final int minExpectedRows;

    private volatile Map<String, Long> byTicker;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public SecTickerCikCache(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter, properties.getSecEdgarFilesBaseUrl(),
                MIN_EXPECTED_ROWS);
    }

    /** MockWebServer 테스트 전용 — base URL과 하한을 바꿔 끼운다. */
    SecTickerCikCache(ExternalApiClientFactory clientFactory,
                      DataSourceProperties properties,
                      RateLimiter rateLimiter,
                      String baseUrl,
                      int minExpectedRows) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.client = clientFactory.create(source, baseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.userAgentPresent = source.hasApiKey();
        this.minExpectedRows = minExpectedRows;
    }

    /**
     * 그 티커의 CIK. <b>모르면 비어 있다 — 예외가 아니다.</b>
     *
     * @param ticker 시세 소스 표기 그대로({@code BRK.A}). 정규화는 여기서 한다.
     */
    public Optional<Long> findCik(String ticker) throws DataSourceException {
        Map<String, Long> map = load();
        Long direct = map.get(ticker);
        if (direct != null) {
            return Optional.of(direct);
        }
        return Optional.ofNullable(map.get(ticker.replace('.', '-')));
    }

    public int size() throws DataSourceException {
        return load().size();
    }

    /** URL에 쓰는 형태. 10자리 zero-padding이 없으면 404다. */
    public static String padded(long cik) {
        return "CIK%010d".formatted(cik);
    }

    private Map<String, Long> load() throws DataSourceException {
        Map<String, Long> cached = byTicker;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (byTicker == null) {
                byTicker = download();
                log.info("SEC 티커→CIK 매핑을 받았다. {}건", byTicker.size());
            }
            return byTicker;
        }
    }

    private Map<String, Long> download() throws DataSourceException {
        if (!userAgentPresent) {
            throw new DataSourceException(DataSourceException.Kind.AUTH,
                    SecEdgarResponse.SOURCE_NAME,
                    "SEC_EDGAR_USER_AGENT가 비어 있다. SEC는 신원을 밝히지 않는 요청을 차단한다.");
        }
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED,
                    SecEdgarResponse.SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + PATH);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "호출 대기 중 인터럽트됐다. " + PATH, e);
        }

        ResponseEntity<JsonNode> response;
        try {
            response = client.get().uri(PATH).retrieve().toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "매핑 파일 호출에 실패했다. " + PATH, e);
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new DataSourceException(SecEdgarResponse.kindOf(response.getStatusCode().value()),
                    SecEdgarResponse.SOURCE_NAME,
                    "HTTP %d. %s".formatted(response.getStatusCode().value(), PATH));
        }
        return parse(response.getBody(), minExpectedRows);
    }

    private static Map<String, Long> parse(JsonNode root, int minExpectedRows)
            throws DataSourceException {
        if (root == null || !root.has("fields") || !root.path("data").isArray()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    SecEdgarResponse.SOURCE_NAME,
                    "fields/data 구조가 아니다 — 매핑 파일의 스펙이 바뀌었다.");
        }
        Map<String, Integer> at = new LinkedHashMap<>();
        JsonNode fields = root.path("fields");
        for (int i = 0; i < fields.size(); i++) {
            at.put(fields.get(i).asString(""), i);
        }
        Integer tickerAt = at.get("ticker");
        Integer cikAt = at.get("cik");
        if (tickerAt == null || cikAt == null) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    SecEdgarResponse.SOURCE_NAME, "fields에 ticker·cik이 없다: " + at.keySet());
        }

        JsonNode data = root.path("data");
        if (data.size() < minExpectedRows) {
            // MUST: 하한은 실측의 절반이고 측정 일자를 상수 옆에 적는다(ADR-0086 §규칙).
            // 추정치에서 뽑은 상수는 소스가 반쯤 준 날 울지 않는다. 반쯤 온 파일을 그대로
            // 쓰면 빠진 종목이 조용히 「CIK를 모른다」가 되고, 그것은 정상과 구분되지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    SecEdgarResponse.SOURCE_NAME,
                    "행이 %d개뿐이다(하한 %d, 2026-08-29 실측 10,391의 절반). 부분 응답으로 본다."
                            .formatted(data.size(), minExpectedRows));
        }

        Map<String, Long> map = new HashMap<>(data.size() * 2);
        for (JsonNode row : data) {
            String ticker = row.get(tickerAt).asString("");
            if (!ticker.isBlank()) {
                map.put(ticker, row.get(cikAt).asLong());
            }
        }
        return Map.copyOf(map);
    }
}
