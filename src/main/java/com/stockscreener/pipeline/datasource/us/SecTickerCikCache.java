package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import com.stockscreener.pipeline.datasource.http.RefetchWaits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *
 * <h2>받은 지 {@link #MAX_AGE}가 지나면 다시 받는다 — 실패의 계약은 소비자가 고른다</h2>
 * <p>웹 JVM은 몇 주씩 산다. 한 번만 받으면 그 사이 상장·개명·거래소 이전이 「CIK를 모른다」·
 * 「옛 거래소」로 굳고, 그것은 정상(ETF·우선주의 빈 매핑)과 구분되지 않는다(ADR-0171).
 * <ul>
 *   <li><b>명부는 {@link #freshSnapshot()}</b> — 다시 받기에 실패하면 던진다. 낡은 거래소 라벨이 명부를
 *       틀리게 만들고, 던지면 리밸런싱이 이전 구성을 지킨다(ADR-0008).</li>
 *   <li><b>종목 단위 소비자({@link #findCik} — 재무·개황)는 {@link #snapshot()}</b> — 실패하면 옛 판을
 *       나이와 함께 WARN하고 <b>낡은 판</b>으로 계속 쓴다. 낡은 판에 있는 티커는 그대로 찾고, <b>낡은 판에 없는 티커는
 *       {@code TRANSIENT}로 던진다</b> — 「모름」은 소스의 답이 아니다(ADR-0174). 판을 하나도 못 얻었으면 {@code AUTH}·
 *       {@code RATE_LIMITED} 말고는 {@code TRANSIENT}로 감싸 던진다(원래 종류는 메시지와 원인에 남는다).</li>
 * </ul>
 * <p><b>신선한 판</b> = 받은 지 {@link #MAX_AGE} 안이고 그 뒤 다시 받기 실패가 없는 판({@link Snapshot#fresh()}). 거기에 없는
 * 티커만 {@link Optional#empty()}이고, 재무 어댑터가 그것을 「소스가 답한 부재」로 쓴다.
 *
 * <p><b>받기가 실패하면 2초 · 10초를 기다리며 두 번 다시 받고({@link RefetchWaits}), 그래도 실패면 {@link #RETRY_AFTER_FAILURE}
 * 동안 기억한다 — 첫 적재도 같다.</b>
 * 기억하지 않으면 종목 루프가 종목마다 다시 받는다 — hang이면 1,000종목 × 읽기 제한 60초다(2026-10-07 반증). 첫 적재
 * 실패를 기억하면 그 10분 동안 미국 종목이 콜 없이 {@code TRANSIENT}로 실패하는데, 그것은 「미국 미완료」로 시계를 안 밀고
 * 다음 차례에 다시 돈다 — 옛 규칙(첫 적재는 기억 안 함)의 사유는 그 실패가 시계를 밀던 분류 위에 서 있었다.
 *
 * <p><b>MUST: 한 실행 안에서는 판 하나를 잡고 쓴다.</b> 행마다 {@link #findCik}를 부르면 경계가 실행
 * 도중에 걸려 한 리밸런싱이 두 판을 섞는다 — 같은 CIK 묶음의 반은 옛 판, 반은 새 판으로 갈린다.
 */
@Component
public class SecTickerCikCache {

    private static final Logger log = LoggerFactory.getLogger(SecTickerCikCache.class);
    private static final String PATH = "/files/company_tickers_exchange.json";

    /** 실측 10,391행의 절반. 소스가 반쯤 준 날 울게 하려는 하한이다(2026-08-29 측정). */
    private static final int MIN_EXPECTED_ROWS = 5_195;

    /** 이보다 오래된 판이면 다음 {@link #snapshot()}에서 다시 받는다. 리밸런싱이 주 1회라 하루면 그 주의 상장을 싣는다. */
    static final Duration MAX_AGE = Duration.ofHours(24);

    /** 다시 받기가 실패한 뒤 이만큼은 다시 부르지 않는다. 미국 FR 한 바퀴(약 16분) 안에 두세 번만 다시 묻는다. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean userAgentPresent;
    private final int minExpectedRows;
    private final Clock clock;
    private final RefetchWaits waits;

    private volatile Snapshot current;
    /** 마지막 다시 받기 실패 — {@code this}로 잠근 자리에서만 읽고 쓴다. */
    private Instant lastFailedAt;
    private DataSourceException lastFailure;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public SecTickerCikCache(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter, properties.getSecEdgarFilesBaseUrl(),
                MIN_EXPECTED_ROWS, Clock.systemUTC(), RefetchWaits.standard());
    }

    /** MockWebServer 테스트 전용 — base URL과 하한을 바꿔 끼운다. */
    SecTickerCikCache(ExternalApiClientFactory clientFactory,
                      DataSourceProperties properties,
                      RateLimiter rateLimiter,
                      String baseUrl,
                      int minExpectedRows) {
        this(clientFactory, properties, rateLimiter, baseUrl, minExpectedRows, Clock.systemUTC(), RefetchWaits.standard());
    }

    /** 위와 같되 시계와 다시 받기 대기까지 바꿔 끼운다 — {@link #MAX_AGE} 경계와 재시도를 시험한다. */
    SecTickerCikCache(ExternalApiClientFactory clientFactory,
                      DataSourceProperties properties,
                      RateLimiter rateLimiter,
                      String baseUrl,
                      int minExpectedRows,
                      Clock clock,
                      RefetchWaits waits) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.client = clientFactory.create(source, baseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.userAgentPresent = source.hasApiKey();
        this.minExpectedRows = minExpectedRows;
        this.clock = clock;
        this.waits = waits;
    }

    /**
     * 그 티커의 CIK. <b>신선한 판이 모르면 비어 있다 — 예외가 아니다.</b> 낡은 판이 모르면 {@code TRANSIENT}로 던진다
     * (클래스 javadoc).
     *
     * @param ticker 시세 소스 표기 그대로({@code BRK.A}). 정규화는 여기서 한다.
     */
    public Optional<Long> findCik(String ticker) throws DataSourceException {
        Snapshot snapshot = snapshot();
        Optional<Long> cik = snapshot.findCik(ticker);
        if (cik.isEmpty() && !snapshot.fresh()) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME,
                    "매핑 갱신 실패 판(%s에 받음)에서 미매핑 — 다음 실행이 다시 묻는다: %s"
                            .formatted(snapshot.loadedAt(), ticker));
        }
        return cik;
    }

    public int size() throws DataSourceException {
        return snapshot().size();
    }

    /**
     * 명부가 쓰는 판. 받은 지 {@link #MAX_AGE}가 지났으면 다시 받고, <b>실패하면 던진다</b>(클래스 javadoc).
     * 한 실행은 이것 하나를 잡고 쓴다.
     */
    public Snapshot freshSnapshot() throws DataSourceException {
        return obtain(true);
    }

    /**
     * 종목 단위 소비자가 쓰는 판. 다시 받기에 실패하면 옛 판을 WARN과 함께 <b>낡은 판</b>으로 낸다. 판이 하나도 없을 때만
     * 던지고, 그때 {@code AUTH}·{@code RATE_LIMITED}가 아니면 {@code TRANSIENT}로 감싼다.
     */
    public Snapshot snapshot() throws DataSourceException {
        try {
            return obtain(false);
        } catch (DataSourceException e) {
            if (e.kind() == DataSourceException.Kind.AUTH || e.kind() == DataSourceException.Kind.RATE_LIMITED) {
                throw e;
            }
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME,
                    "티커→CIK 매핑을 얻지 못했다(" + e.kind() + ": " + e.getMessage() + ") — 다음 실행이 다시 묻는다.", e);
        }
    }

    private Snapshot obtain(boolean strict) throws DataSourceException {
        Snapshot held = current;
        if (held != null && !held.olderThan(MAX_AGE, clock.instant())) {
            return held;
        }
        synchronized (this) {
            held = current;
            Instant now = clock.instant();
            if (held != null && !held.olderThan(MAX_AGE, now)) {
                return held;
            }
            if (lastFailedAt != null && lastFailedAt.plus(RETRY_AFTER_FAILURE).isAfter(now)) {
                // 방금 실패했다 — 다시 안 부른다(콜 0). 명부와 첫 적재는 같은 실패를 그대로, 종목 소비자는 낡은 판을 낸다.
                if (strict || held == null) {
                    throw lastFailure;
                }
                return held.stale();
            }
            DataSourceException failure = null;
            for (int attempt = 0; attempt <= waits.retries(); attempt++) {
                if (attempt > 0) {
                    if (failure.kind() == DataSourceException.Kind.AUTH
                            || failure.kind() == DataSourceException.Kind.RATE_LIMITED) {
                        break; // 인증·한도는 다시 물어도 같다.
                    }
                    try {
                        // 대기 없이 다시 물으면 0.4초 안에 끝나 그보다 긴 끊김을 못 넘는다 — 순간 끊김 하나를 10분 기억으로
                        // 만들지 않는 것은 이 대기다(RefetchWaits).
                        waits.before(attempt - 1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failure = new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME,
                                "매핑을 다시 받기를 기다리는 중 인터럽트됐다.", e);
                        break;
                    }
                }
                try {
                    return loaded(download(), clock.instant(), held);
                } catch (DataSourceException e) {
                    failure = e;
                }
            }
            now = clock.instant();
            lastFailedAt = now;
            lastFailure = failure;
            if (strict || held == null) {
                throw failure;
            }
            log.warn("SEC 티커→CIK 매핑을 다시 받지 못해 {}에 받은 판을 낡은 판으로 계속 쓴다 — 그 판에 없는 티커는 "
                    + "TRANSIENT다. {}분 뒤 다시 묻는다. 원인={}", held.loadedAt(),
                    RETRY_AFTER_FAILURE.toMinutes(), failure.getMessage());
            return held.stale();
        }
    }

    private Snapshot loaded(Map<String, Listing> mapping, Instant now, Snapshot held) {
        Snapshot fresh = new Snapshot(mapping, now);
        current = fresh;
        lastFailedAt = null;
        lastFailure = null;
        if (held == null) {
            log.info("SEC 티커→CIK 매핑을 받았다. {}건", fresh.size());
        } else {
            log.info("SEC 티커→CIK 매핑을 받았다. {}건 — 앞 판은 {}에 받은 {}건이었다",
                    fresh.size(), held.loadedAt(), held.size());
        }
        return fresh;
    }

    /** 한 줄의 CIK와 SEC가 적은 거래소. 거래소는 비어 있을 수 있다(실측 182행 — 파일이 {@code null}로 싣는다). */
    public record Listing(long cik, String exchange) {
    }

    /**
     * 한 번 받은 매핑. 불변이다.
     *
     * <p>{@code exchange}는 SEC의 표기({@code NYSE}·{@code Nasdaq}·{@code OTC}·{@code CBOE})다. <b>SEC는
     * NYSE American·Arca도 「NYSE」로 적는다</b>(실측 UEC·SEB·PHYS) — 그 둘을 가르려면 시세 소스의
     * 단건 조회가 필요하다(ADR-0171).
     */
    public static final class Snapshot {
        private final Map<String, Listing> byTicker;
        private final Instant loadedAt;
        private final boolean fresh;

        Snapshot(Map<String, Listing> byTicker, Instant loadedAt) {
            this(byTicker, loadedAt, true);
        }

        private Snapshot(Map<String, Listing> byTicker, Instant loadedAt, boolean fresh) {
            this.byTicker = byTicker;
            this.loadedAt = loadedAt;
            this.fresh = fresh;
        }

        /**
         * 신선한 판인가 — 받은 지 {@link #MAX_AGE} 안이고 그 뒤 다시 받기 실패가 없다. 거짓이면 이 판의 부재는 소스의 답이
         * 아니다(ADR-0174).
         */
        public boolean fresh() {
            return fresh;
        }

        Snapshot stale() {
            return new Snapshot(byTicker, loadedAt, false);
        }

        public Optional<Long> findCik(String ticker) {
            return find(ticker).map(Listing::cik);
        }

        /** SEC가 적은 거래소. 티커가 없거나 거래소가 비면 비어 있다. */
        public Optional<String> exchangeOf(String ticker) {
            return find(ticker).map(Listing::exchange);
        }

        public int size() {
            return byTicker.size();
        }

        public Instant loadedAt() {
            return loadedAt;
        }

        boolean olderThan(Duration age, Instant now) {
            return !loadedAt.plus(age).isAfter(now);
        }

        private Optional<Listing> find(String ticker) {
            Listing direct = byTicker.get(ticker);
            if (direct != null) {
                return Optional.of(direct);
            }
            return Optional.ofNullable(byTicker.get(ticker.replace('.', '-')));
        }
    }

    /** URL에 쓰는 형태. 10자리 zero-padding이 없으면 404다. */
    public static String padded(long cik) {
        return "CIK%010d".formatted(cik);
    }

    private Map<String, Listing> download() throws DataSourceException {
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

        // 본문을 문자열로 받아 상태를 먼저 본다 — JSON으로 받으면 403·429 HTML이 상태를 보기 전에 변환에서 터져 TRANSIENT가
        // 되고, 한도를 「다시 묻지 않음」에서 비켜 막힌 호스트를 한 번 더 친다(구현 반증). 판정은 다른 SEC 받기와 같은 자리다.
        ResponseEntity<String> response;
        try {
            response = client.get().uri(PATH).retrieve().toEntity(String.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "매핑 파일 호출에 실패했다. " + PATH, e);
        }
        return parse(SecEdgarResponse.requireJson(response.getStatusCode(), response.getBody(), PATH),
                minExpectedRows);
    }

    /** 매핑 파일 본문을 읽는다 — 공시 목록 재생 시험이 저장한 본문을 같은 해석으로 먹인다. */
    static Map<String, Listing> parse(JsonNode root, int minExpectedRows)
            throws DataSourceException {
        if (root == null || root.isMissingNode() || root.isNull()) {
            // 본문이 없는 것은 받기 실패다(끊긴 전송) — 스펙 변경과 가른다.
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "매핑 파일 본문이 없다. " + PATH);
        }
        if (!root.has("fields") || !root.path("data").isArray()) {
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
        Integer exchangeAt = at.get("exchange");
        if (tickerAt == null || cikAt == null || exchangeAt == null) {
            // 거래소는 상장 판정의 재료다(ADR-0171) — 빠지면 숨은 상장이 전부 「거래소 모름」으로 조용히 빠진다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED,
                    SecEdgarResponse.SOURCE_NAME, "fields에 ticker·cik·exchange가 없다: " + at.keySet());
        }

        JsonNode data = root.path("data");
        if (data.size() < minExpectedRows) {
            // MUST: 하한은 실측의 절반이고 측정 일자를 상수 옆에 적는다(ADR-0086 §규칙).
            // 추정치에서 뽑은 상수는 소스가 반쯤 준 날 울지 않는다. 반쯤 온 파일을 그대로
            // 쓰면 빠진 종목이 조용히 「CIK를 모른다」가 되고, 그것은 정상과 구분되지 않는다.
            // 받기 실패(덜 온 파일)로 본다 — 소스가 답한 것이 아니다(ADR-0174).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME,
                    "행이 %d개뿐이다(하한 %d, 2026-08-29 실측 10,391의 절반). 부분 응답으로 본다."
                            .formatted(data.size(), minExpectedRows));
        }

        Map<String, Listing> map = new HashMap<>(data.size() * 2);
        for (JsonNode row : data) {
            String ticker = row.get(tickerAt).asString("");
            if (!ticker.isBlank()) {
                JsonNode exchange = row.get(exchangeAt);
                map.put(ticker, new Listing(row.get(cikAt).asLong(),
                        exchange == null || exchange.isNull() ? null : exchange.asString("")));
            }
        }
        return Map.copyOf(map);
    }
}
