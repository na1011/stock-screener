package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import com.stockscreener.pipeline.datasource.http.RefetchWaits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 종목코드 → DART 고유번호(`corp_code`) 매핑.
 *
 * <p>DART의 재무 API는 종목코드가 아니라 8자리 {@code corp_code}를 받는다. 그 매핑을 주는
 * <b>유일한 공식 경로</b>가 {@code corpCode.xml}이며, <b>ZIP 바이너리</b>로 온다.
 * <b>이 엔드포인트 계약의 정본이 여기다</b> — 해석 지점이 저장소에 이 클래스 하나뿐이다.
 *
 * <p>요청 인자는 {@code crtfc_key} 하나. 응답은 3.5MB ZIP이고 안에 {@code CORPCODE.xml} 한 개가
 * 들어 있다. 필드는 {@code corp_code}(8자리) · {@code corp_name} · {@code corp_eng_name} ·
 * {@code stock_code}(6자리, <b>상장사만 채워진다</b>) · {@code modify_date} 다섯이다.
 *
 * <p>테이블이 아니라 인메모리로 두는 이유는 되돌리기 쉽고 규모가 작기 때문이다 —
 * 실측 기준 전체 118,664법인 중 상장사는 3,925건뿐이다(앱 로그 2026-10-05·10-06 적재는 3,998건). 영속화가 필요해지면 그때 옮긴다.
 *
 * <p>지연 로딩한다. 기동 시 3.5MB를 받으면 키가 없는 개발 환경에서 애플리케이션이 뜨지
 * 못하고, 재무 수집을 하지 않는 날에도 매번 받게 된다.
 *
 * <h2>받은 지 {@link #MAX_AGE}가 지나면 다시 받는다 — 낡은 판의 부재는 「답」이 아니다(ADR-0174)</h2>
 * <p>웹 JVM은 며칠씩 산다(2026-10-05 15:43 → 10-06 18:44 27시간). 한 번만 받으면 그 뒤의 신규 상장이 「DART가 모른다」로
 * 굳고, 그것은 소스가 답한 것과 구분되지 않는다. 그래서 {@code SecTickerCikCache}와 같은 구조를 쓴다.
 * <ul>
 *   <li><b>신선한 판</b> = 받은 지 {@link #MAX_AGE} 안이고 그 뒤 다시 받기 실패가 없는 판. 거기에 없는 종목코드만
 *       {@link Optional#empty()}다 — 어댑터가 그것을 「소스가 답한 부재」로 쓴다.</li>
 *   <li><b>다시 받기 실패 · 하한 미달</b>은 옛 판을 유지하고 그 판을 <b>낡은 판</b>으로 낸다. 낡은 판에 없는 종목코드는
 *       {@code TRANSIENT}로 던진다 — 「매핑 갱신 실패 판에서 미매핑 — 다음 실행이 다시 묻는다」.</li>
 *   <li><b>2초 · 10초를 기다리며 두 번 다시 받고({@link RefetchWaits}), 그래도 실패면 {@link #RETRY_AFTER_FAILURE} 동안
 *       기억한다</b> — 첫 적재도 같다. 그동안
 *       종목 조회는 콜 없이 {@code TRANSIENT}다(옛 판이 있으면 그 판으로 찾는다). 기억하지 않으면 종목마다 3.5MB를
 *       다시 받는다(거버넌스 국내 종목당 23회 · 약 8,050번).</li>
 *   <li><b>종목 조회({@link #findCorpCode})에서 적재 실패는 {@code AUTH}·{@code RATE_LIMITED}만 빼고 {@code TRANSIENT}로
 *       감싼다</b> — 첫 적재의 {@code 014}가 종목마다 {@code NOT_FOUND}로 남으면 「DART가 모른다」로 읽힌다. 원래 종류는
 *       메시지와 원인에 남는다.</li>
 * </ul>
 */
@Component
public class DartCorpCodeCache {

    /** 요청 경로 — 일일 총량을 이 경로로 센다(ADR-0176). */
    private static final String PATH = "/corpCode.xml";

    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeCache.class);

    /** 이보다 오래된 판이면 다음 조회에서 다시 받는다. 무거운 잡이 하루 하나라 하루면 그날의 신규 상장을 싣는다. */
    static final Duration MAX_AGE = Duration.ofHours(24);

    /** 다시 받기가 실패한 뒤 이만큼은 다시 부르지 않는다({@code SecTickerCikCache}와 같다). */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    /**
     * 상장사 수의 하한 — 앱 로그 2026-10-05 14:00:00 · 10-06 15:54:54 「상장사 3998건」의 절반(0콜 측정 · 2026-10-07).
     * 소스가 반쯤 준 날(잘린 ZIP · 빈 목록) 그 판을 쓰면 빠진 종목이 조용히 「DART가 모른다」가 된다.
     */
    static final int MIN_EXPECTED_LISTED = 1_999;

    /**
     * {@code <list>} 블록 하나에서 corp_code와 stock_code를 뽑는다.
     *
     * <p>StAX 대신 정규식을 쓰는 이유는 구조가 평평하고(list 안에 필드 5개) 네임스페이스가
     * 없어서다. XML 파서 의존성을 추가할 이유가 되지 못한다.
     */
    private static final Pattern LIST_BLOCK = Pattern.compile("<list>(.*?)</list>", Pattern.DOTALL);
    private static final Pattern CORP_CODE = Pattern.compile("<corp_code>(.*?)</corp_code>", Pattern.DOTALL);
    private static final Pattern STOCK_CODE = Pattern.compile("<stock_code>(.*?)</stock_code>", Pattern.DOTALL);
    private static final Pattern CORP_NAME = Pattern.compile("<corp_name>(.*?)</corp_name>", Pattern.DOTALL);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final String apiKey;
    private final int minExpectedListed;
    private final Clock clock;
    private final RefetchWaits waits;

    private volatile Snapshot current;
    /** 마지막 받기 실패 — {@code this}로 잠근 자리에서만 읽고 쓴다. */
    private Instant lastFailedAt;
    private DataSourceException lastFailure;

    // MUST: @Autowired를 지우지 않는다. 생성자가 둘이면 Spring이 고르지 못해 컨텍스트가 기동에 실패한다.
    @Autowired
    public DartCorpCodeCache(com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory factory,
                             DataSourceProperties properties,
                             @Qualifier("dartRateLimiter") RateLimiter rateLimiter) {
        this(factory, properties, rateLimiter, MIN_EXPECTED_LISTED, Clock.systemUTC(), RefetchWaits.standard());
    }

    /** 시험 전용 — 하한 · 시계 · 다시 받기 대기를 바꿔 끼운다. */
    DartCorpCodeCache(com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory factory,
                      DataSourceProperties properties, RateLimiter rateLimiter, int minExpectedListed, Clock clock,
                      RefetchWaits waits) {
        DataSourceProperties.Source source = properties.getDart();
        this.client = factory.create(source);
        this.rateLimiter = rateLimiter;
        this.apiKey = source.getApiKey();
        this.minExpectedListed = minExpectedListed;
        this.clock = clock;
        this.waits = waits;
    }

    /**
     * 종목코드에 대응하는 고유번호를 찾는다.
     *
     * <p>{@link Optional#empty()}는 <b>신선한 판</b>에 그 종목코드가 없다는 뜻이다 — 「DART가 이 종목코드를 모른다」. 신규
     * 상장 직후나 상장폐지 종목에서 정상적으로 발생하므로 예외가 아니다. 낡은 판에 없으면 {@code TRANSIENT}로 던진다(클래스 javadoc).
     *
     * @throws DataSourceException 판을 못 얻었거나(첫 적재 실패 — {@code AUTH}·{@code RATE_LIMITED} 말고는 {@code TRANSIENT}),
     *                             낡은 판에 없을 때
     */
    public Optional<String> findCorpCode(String stockCode) throws DataSourceException {
        Snapshot snapshot = snapshot();
        String corpCode = snapshot.byStockCode().get(stockCode);
        if (corpCode != null) {
            return Optional.of(corpCode);
        }
        if (!snapshot.fresh()) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
                    "매핑 갱신 실패 판(%s에 받음)에서 미매핑 — 다음 실행이 다시 묻는다: %s"
                            .formatted(snapshot.loadedAt(), stockCode));
        }
        return Optional.empty();
    }

    /** 매핑된 상장사 수. 로그와 진단에 쓴다. */
    public int size() throws DataSourceException {
        return snapshot().byStockCode().size();
    }

    /**
     * 종목 조회가 쓰는 판. 다시 받기에 실패하면 옛 판을 낡은 판으로 낸다 — 판이 하나도 없을 때만 던지고, 그때
     * {@code AUTH}·{@code RATE_LIMITED}가 아니면 {@code TRANSIENT}로 감싼다.
     */
    Snapshot snapshot() throws DataSourceException {
        try {
            return obtain();
        } catch (DataSourceException e) {
            if (e.kind() == DataSourceException.Kind.AUTH || e.kind() == DataSourceException.Kind.RATE_LIMITED) {
                throw e;
            }
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
                    "고유번호 매핑을 얻지 못했다(" + e.kind() + ": " + e.getMessage() + ") — 다음 실행이 다시 묻는다.", e);
        }
    }

    private Snapshot obtain() throws DataSourceException {
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
                // 방금 실패했다 — 다시 안 부른다(콜 0). 옛 판이 있으면 낡은 판으로, 없으면 같은 실패를 그대로 낸다.
                if (held == null) {
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
                        failure = new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
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
            if (held == null) {
                throw failure;
            }
            log.warn("DART 고유번호 매핑을 다시 받지 못해 {}에 받은 판을 낡은 판으로 계속 쓴다 — 그 판에 없는 종목코드는 "
                    + "TRANSIENT다. {}분 뒤 다시 묻는다. 원인={}", held.loadedAt(), RETRY_AFTER_FAILURE.toMinutes(),
                    failure.getMessage());
            return held.stale();
        }
    }

    private Snapshot loaded(Map<String, String> mapping, Instant now, Snapshot held) {
        Snapshot fresh = new Snapshot(mapping, now, true);
        current = fresh;
        lastFailedAt = null;
        lastFailure = null;
        if (held == null) {
            log.info("DART 고유번호 매핑을 적재했다. 상장사 {}건", mapping.size());
        } else {
            log.info("DART 고유번호 매핑을 다시 받았다. 상장사 {}건 — 앞 판은 {}에 받은 {}건이었다",
                    mapping.size(), held.loadedAt(), held.byStockCode().size());
        }
        return fresh;
    }

    /**
     * 한 번 받은 매핑. 불변이다.
     *
     * @param fresh 신선한 판인가 — 받은 지 {@link #MAX_AGE} 안이고 그 뒤 다시 받기 실패가 없다. 거짓이면 그 판의 부재는
     *              소스의 답이 아니다
     */
    record Snapshot(Map<String, String> byStockCode, Instant loadedAt, boolean fresh) {

        boolean olderThan(Duration age, Instant now) {
            return !loadedAt.plus(age).isAfter(now);
        }

        Snapshot stale() {
            return new Snapshot(byStockCode, loadedAt, false);
        }
    }

    private Map<String, String> download() throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        DartResponse.acquire(rateLimiter, PATH, "corpCode.xml");

        ResponseEntity<byte[]> response;
        try {
            response = client.get()
                    .uri(uri -> uri.path(PATH).queryParam("crtfc_key", apiKey).build())
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "corpCode.xml 다운로드에 실패했다.", e);
        }

        // 상태를 먼저 본다 — 점검 페이지·프록시 오류의 본문을 ZIP·오류 JSON으로 읽지 않는다(DartResponse.read와 같은 규칙).
        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new DataSourceException(status == 429
                    ? DataSourceException.Kind.RATE_LIMITED : DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "corpCode.xml HTTP " + status);
        }
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "corpCode.xml 응답이 비어 있다.");
        }

        // IMPORTANT — content-type이 application/x-msdownload로 온다(실측). 타입으로 분기하면
        // 실패하므로, ZIP 매직넘버(PK)로 판별한다. 인증 실패 시에는 JSON 오류가 오므로
        // 그 경우를 여기서 걸러야 "ZIP이 아니다"라는 엉뚱한 메시지를 피한다.
        if (body.length < 2 || body[0] != 'P' || body[1] != 'K') {
            String asText = new String(body, StandardCharsets.UTF_8);
            throw DartResponse.errorFromBody(asText, "corpCode.xml");
        }

        String xml = unzipFirstEntry(body);
        Map<String, String> mapping = parse(xml);
        if (mapping.size() < minExpectedListed) {
            // 반쯤 온 파일을 쓰면 빠진 종목이 조용히 「DART가 모른다」가 된다 — 받기 실패로 보고 옛 판을 지킨다.
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
                    "상장사가 %d건뿐이다(하한 %d — 2026-10-05·10-06 앱 로그 3,998의 절반). 부분 응답으로 본다."
                            .formatted(mapping.size(), minExpectedListed));
        }
        return Map.copyOf(mapping);
    }

    private static String unzipFirstEntry(byte[] zipBytes) throws DataSourceException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                // 빈 ZIP · 잘린 ZIP은 받기 실패다(끊긴 전송) — 소스가 답한 것이 아니다.
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                        DartResponse.SOURCE_NAME, "corpCode ZIP이 비어 있다.");
            }
            return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "corpCode ZIP을 풀 수 없다 — 끊긴 전송일 수 있다.", e);
        }
    }

    /**
     * 상장사만 추린다. {@code stock_code}가 빈 법인이 대부분(비상장)이다.
     */
    private static Map<String, String> parse(String xml) {
        Map<String, String> mapping = new HashMap<>();
        Matcher blocks = LIST_BLOCK.matcher(xml);
        while (blocks.find()) {
            String block = blocks.group(1);
            String stockCode = group(STOCK_CODE, block);
            if (stockCode == null || stockCode.isBlank()) {
                continue;
            }
            String corpCode = group(CORP_CODE, block);
            if (corpCode == null || corpCode.isBlank()) {
                continue;
            }
            // 같은 종목코드가 두 번 나오면 뒤엣것이 이긴다. 실측에서는 중복이 없었다.
            String previous = mapping.put(stockCode.strip(), corpCode.strip());
            if (previous != null && !previous.equals(corpCode.strip())) {
                log.warn("종목코드 {}에 고유번호가 둘이다: {} vs {} — 뒤엣것을 쓴다. 회사명={}",
                        stockCode, previous, corpCode, group(CORP_NAME, block));
            }
        }
        return mapping;
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
