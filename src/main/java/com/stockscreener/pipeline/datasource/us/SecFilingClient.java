package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 한 발행사의 <b>공시 목록</b>(submissions)과 <b>공시 파일</b>(Archives)을 받는다 — companyfacts가 빠뜨린 공시를 보충하는
 * 원장이다(ADR-0173).
 *
 * <p><b>계약</b>(실측은 {@code docs/api-spec/sec-edgar-financial.md} 「공시 보충」 절):
 * <ul>
 *   <li>{@code GET data.sec.gov/submissions/CIK##########.json} — {@code filings.recent}가 최근 공시 1,000건 이상을 열
 *       배열로 싣고, 더 오래된 것은 {@code filings.files}의 페이지({@code filingFrom}·{@code filingTo})가 같은 열 배열로
 *       싣는다.</li>
 *   <li>{@code GET www.sec.gov/Archives/edgar/data/{cik}/{accn 숫자만}/index.json} — 공시 폴더의 파일 목록.</li>
 *   <li>인스턴스 파일 — 그 목록의 {@code .xml} 중 {@code FilingSummary.xml}과 링크베이스({@code _cal}·{@code _def}·
 *       {@code _lab}·{@code _pre}·{@code _ref})를 뺀 <b>하나</b>다. 인라인 공시는 {@code …_htm.xml}, 비인라인은
 *       {@code btu-20180331.xml} 꼴이다.</li>
 * </ul>
 *
 * <p><b>리미터는 companyfacts와 공유한다</b> — SEC의 초당 한도는 사용자 단위로 두 호스트에 함께 걸린다.
 *
 * <p><b>{@code TRANSIENT}만 두 번 다시 묻는다.</b> 공시 하나에 콜이 둘(색인 · 인스턴스)이고 종목마다 목록 콜이 붙으므로,
 * 다시 묻지 않으면 5xx 한 번이 그 종목의 그 달 보충을 잃는다. 404·403은 다시 물어도 같다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecFilingClient {

    /** 다시 묻는 횟수(처음 한 번을 빼고). */
    static final int TRANSIENT_RETRIES = 2;

    /**
     * 공시 하나.
     *
     * @param xbrl 원장이 이 공시에 XBRL이 있다고 말하는가({@code isXBRL == 1}). 없으면 보충할 인스턴스가 없다.
     */
    public record Filing(String accession, String form, LocalDate filed, LocalDate reportDate, boolean xbrl) {
    }

    private final RestClient data;
    private final RestClient files;
    private final RateLimiter rateLimiter;

    @Autowired
    public SecFilingClient(ExternalApiClientFactory clientFactory,
                           DataSourceProperties properties,
                           @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter,
                properties.getSecEdgar().getBaseUrl(), properties.getSecEdgarFilesBaseUrl());
    }

    /** MockWebServer 시험 전용 — 두 호스트를 바꿔 끼운다. */
    SecFilingClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                    RateLimiter rateLimiter, String dataBaseUrl, String filesBaseUrl) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.data = clientFactory.create(source, dataBaseUrl, SecEdgarHeaders.of(source));
        this.files = clientFactory.create(source, filesBaseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
    }

    /**
     * {@code since} 이후에 제출된 공시 전부. {@code recent}로 모자라면 그 날짜에 닿을 때까지 페이지를 따라간다.
     */
    public List<Filing> filings(long cik, LocalDate since) throws DataSourceException {
        // padded는 「CIK」 접두까지 붙인 10자리다(CIK0000320193) — companyfacts와 같은 이름 규칙.
        String padded = SecTickerCikCache.padded(cik);
        String context = "submissions " + padded;
        JsonNode root = json(() -> data.get().uri("/submissions/{cik}.json", padded)
                .retrieve().toEntity(String.class), context);
        JsonNode filings = root.path("filings");
        if (!filings.has("recent")) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "200인데 filings.recent가 없다. " + context);
        }
        List<Filing> out = new ArrayList<>(parseFilings(filings.path("recent"), since, context));
        for (JsonNode page : filings.path("files")) {
            String name = page.path("name").asString("");
            LocalDate to = parseDate(page.path("filingTo").asString(""));
            // 페이지는 오래된 공시를 싣는다. 끝이 since보다 앞이면 그 페이지에 받을 것이 없다.
            if (name.isBlank() || (to != null && to.isBefore(since))) {
                continue;
            }
            String pageContext = context + " " + name;
            JsonNode body = json(() -> data.get().uri("/submissions/{name}", name)
                    .retrieve().toEntity(String.class), pageContext);
            out.addAll(parseFilings(body, since, pageContext));
        }
        return out;
    }

    /**
     * 공시 폴더의 파일 이름 전부. <b>받기만 한다</b> — 어느 파일이 인스턴스인지는 해석이라 호출부
     * ({@link SecFilingSupplement#pickInstance})가 정한다. 받기 실패(404·5xx·본문 이상)와 해석 실패는 다시 받을 가치가 다르다.
     *
     * <p><b>MUST: 항목이 없거나 빈 색인은 본문 이상(MALFORMED)이다</b> — 빈 목록으로 내면 「XBRL 산출물 없음」으로 읽혀
     * {@code NO_XBRL}이 되고, 그것은 다시 받지 않는 종료 상태다. 실물 색인은 늘 파일이 있다(2026-10-06 같은 날 캐시
     * {@code index.json} 567개 전부 항목 ≥ 1 · {@code .txt} 포함 · NO_XBRL 37도 각 5개 이상) — 빈 목록은 원장의 사실이 아니라
     * 응답 이상이다. 받기 실패라 다음 실행이 다시 받는다. 이름 없는 항목도 같다(같은 캐시 0개) — 빈 이름은 후보도 산출물도 아니라
     * 그 항목만 빠진 목록이 {@code NO_XBRL}로 읽힐 수 있다.
     *
     * <p>항목마다 원장이 적은 바이트 수({@code size})를 원문 그대로 함께 낸다 — {@link #instance}가 고른 인스턴스의 크기만
     * 읽어 받은 본문과 맞춘다. 색인 html·묶음 {@code .txt}는 크기가 비어 있다(같은 캐시 1,701항목) — 안 쓰는 항목의 크기로
     * 공시 전체를 세우지 않는다(v9 반증).
     *
     * <p>이 파일 전체가 판 범위다({@link XbrlInstanceReader#READER_VERSION}) — 이름 목록이 인스턴스 고르기의 입력이고, 그 결과
     * {@code null}이 다시 받지 않는 {@code NO_XBRL}이다.
     */
    public List<FolderItem> folder(long cik, String accession) throws DataSourceException {
        String folder = accession.replace("-", "");
        String context = "index %d/%s".formatted(cik, folder);
        JsonNode index = json(() -> files.get().uri("/Archives/edgar/data/{cik}/{folder}/index.json", cik, folder)
                .retrieve().toEntity(String.class), context);
        JsonNode items = index.path("directory").path("item");
        if (!items.isArray() || items.isEmpty()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "공시 폴더 색인에 항목이 없다 — 응답 이상이라 다음 실행이 다시 받는다. " + context);
        }
        List<FolderItem> out = new ArrayList<>();
        for (JsonNode item : items) {
            String name = item.path("name").asString("");
            if (name.isEmpty()) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "공시 폴더 색인에 이름 없는 항목이 있다 — 응답 이상이라 다음 실행이 다시 받는다. " + context);
            }
            out.add(new FolderItem(name, item.path("size").asString("").trim()));
        }
        return out;
    }

    static boolean isInstanceCandidate(String name) {
        if (!name.endsWith(".xml") || name.equalsIgnoreCase("FilingSummary.xml")) {
            return false;
        }
        return !isLinkbase(name);
    }

    /** XBRL 산출물인가 — 스키마({@code .xsd})나 링크베이스. 인스턴스가 없는데 이것이 있으면 XBRL은 있는데 못 고른 것이다. */
    static boolean isXbrlArtifact(String name) {
        return name.endsWith(".xsd") || isLinkbase(name);
    }

    private static boolean isLinkbase(String name) {
        if (!name.endsWith(".xml")) {
            return false;
        }
        String base = name.substring(0, name.length() - 4);
        return base.endsWith("_cal") || base.endsWith("_def") || base.endsWith("_lab")
                || base.endsWith("_pre") || base.endsWith("_ref");
    }

    /** 공시 폴더 색인의 한 항목 — 이름과 원장이 적은 바이트 수(원문 · 비어 있을 수 있다). */
    public record FolderItem(String name, String size) {

        /** 받은 본문과 맞출 크기. 비었거나 수가 아니면(19자리를 넘는 것 포함) 받기 실패다 — 다음 실행이 다시 받는다. */
        long expectedSize(String context) throws DataSourceException {
            if (size == null || size.isEmpty()) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "색인에 인스턴스 크기가 없다 — 받은 본문을 맞춰 볼 수 없어 다음 실행이 다시 받는다. " + context);
            }
            try {
                long parsed = Long.parseLong(size);
                if (parsed >= 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                // 아래에서 받기 실패로 던진다 — 런타임 예외가 받기 계약 밖으로 새지 않게.
            }
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "색인의 인스턴스 크기가 수가 아니다: " + size + " — 다음 실행이 다시 받는다. " + context);
        }
    }

    /**
     * 인스턴스 파일의 바이트.
     *
     * <p><b>MUST: 받은 길이가 색인의 크기와 다르면 다시 받고, 끝까지 다르면 받기 실패(MALFORMED)다</b> — 지금 전송(HttpURLConnection)은 고정 길이
     * 본문이 도중에 끊겨도 예외 없이 받은 만큼 넘기고, 200 오류 페이지도 본문이다. 길이를 안 맞추면 둘 다 해석 실패
     * ({@code UNREADABLE})로 접혀 판이 같은 동안 다시 받지 않는 종료 상태가 된다(2026-10-06 v8 반증 — 끊김 · 짧은 본문 ·
     * 200 HTML · XHTML 네 꼴 전부). 원장은 길이를 정직하게 준다(같은 날 캐시 인스턴스 530/530이 크기와 바이트 일치).
     * 같은 실행 안에서 다시 받는 것은 끊긴 전송이 5xx와 같은 일시 실패라서다 — 한 번 끊겼다고 그 공시를 다음 FR까지 비우지
     * 않는다(v9 반증: 503은 3콜 뒤 TRANSIENT인데 끊긴 고정 길이 본문은 1콜로 끝났다). 크기가 빈 항목은 맞춰 볼 수 없어 받지
     * 않는다(같은 캐시의 인스턴스 0개).
     */
    public byte[] instance(long cik, String accession, FolderItem item) throws DataSourceException {
        String folder = accession.replace("-", "");
        String name = item.name();
        String context = "instance %d/%s/%s".formatted(cik, folder, name);
        long expected = item.expectedSize(context);
        ResponseEntity<byte[]> response = withRetry(() -> files.get()
                .uri("/Archives/edgar/data/{cik}/{folder}/{name}", cik, folder, name)
                .retrieve().toEntity(byte[].class), received -> {
                    byte[] body = received.getBody();
                    if (body == null || body.length == 0) {
                        return "200인데 본문이 없다.";
                    }
                    return body.length == expected ? null
                            : "받은 길이 %d가 색인 크기 %d와 다르다 — 받다 만 본문이거나 오류 본문이다.".formatted(body.length, expected);
                }, context);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new DataSourceException(SecEdgarResponse.kindOf(response.getStatusCode().value()),
                    SecEdgarResponse.SOURCE_NAME, "HTTP %d. %s".formatted(response.getStatusCode().value(), context));
        }
        return response.getBody();
    }

    /**
     * 열 배열 표 하나({@code filings.recent} 또는 페이지 본문)에서 {@code since} 이후 공시를 낸다. 수집 도구와 스윕이 같은
     * 해석을 쓰게 정적으로 둔다.
     */
    static List<Filing> parseFilings(JsonNode table, LocalDate since, String context) throws DataSourceException {
        List<Filing> out = new ArrayList<>();
        JsonNode accessions = table.path("accessionNumber");
        JsonNode forms = table.path("form");
        JsonNode filed = table.path("filingDate");
        JsonNode reports = table.path("reportDate");
        JsonNode xbrl = table.path("isXBRL");
        int n = accessions.size();
        if (forms.size() != n || filed.size() != n || xbrl.size() != n) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "열 배열의 길이가 다르다(접수번호 %d · 폼 %d · 제출일 %d · XBRL %d). %s"
                            .formatted(n, forms.size(), filed.size(), xbrl.size(), context));
        }
        for (int i = 0; i < n; i++) {
            LocalDate date = parseDate(filed.get(i).asString(""));
            if (date == null) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "제출일이 날짜가 아니다: %s %s".formatted(filed.get(i), context));
            }
            if (date.isBefore(since)) {
                continue;
            }
            out.add(new Filing(accessions.get(i).asString(""), forms.get(i).asString(""), date,
                    i < reports.size() ? parseDate(reports.get(i).asString("")) : null,
                    xbrl.get(i).asInt(0) == 1));
        }
        return out;
    }

    /**
     * 색인·공시 목록처럼 JSON을 받는다. 2xx인데 JSON으로 안 읽히면 인스턴스의 길이 대조처럼 같은 실행 안에서 다시 받는다 — 한 번
     * 끊긴 색인이 그 공시를, 끊긴 공시 목록이 그 종목의 보충 전부를 다음 FR까지 비우지 않게(v10 반증: 셋 다 1콜로 끝났다).
     */
    private JsonNode json(Supplier<ResponseEntity<String>> call, String context) throws DataSourceException {
        ResponseEntity<String> response = withRetry(call, received -> SecEdgarResponse.unreadableJson(received.getBody()),
                context);
        return SecEdgarResponse.requireJson(response.getStatusCode(), response.getBody(), context);
    }

    /**
     * 한 콜을 리미터 뒤에서 부르고, 전송 실패와 5xx만 {@link #TRANSIENT_RETRIES}번 다시 부른다. 4xx는 그대로 돌려준다 —
     * 상태코드의 판정은 호출부가 한다({@link SecEdgarResponse#kindOf}).
     */
    private <T> ResponseEntity<T> withRetry(Supplier<ResponseEntity<T>> call, String context)
            throws DataSourceException {
        return withRetry(call, received -> null, context);
    }

    /**
     * @param incomplete 2xx인데 덜 온 응답이면 그 사유, 아니면 {@code null} — 덜 온 응답은 일시 실패처럼 다시 받고, 끝까지 그러면
     *                   그 사유의 MALFORMED로 던진다
     */
    private <T> ResponseEntity<T> withRetry(Supplier<ResponseEntity<T>> call,
                                            Function<ResponseEntity<T>, String> incomplete, String context)
            throws DataSourceException {
        DataSourceException last = null;
        for (int attempt = 0; attempt <= TRANSIENT_RETRIES; attempt++) {
            acquire(context);
            ResponseEntity<T> response;
            try {
                response = call.get();
            } catch (RuntimeException e) {
                // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
                last = new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME,
                        "호출에 실패했다. " + context, e);
                continue;
            }
            if (response.getStatusCode().is5xxServerError()) {
                last = new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME,
                        "HTTP %d. %s".formatted(response.getStatusCode().value(), context));
                continue;
            }
            String why = response.getStatusCode().is2xxSuccessful() ? incomplete.apply(response) : null;
            if (why != null) {
                last = new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        why + " 다음 실행이 다시 받는다. " + context);
                continue;
            }
            return response;
        }
        throw last;
    }

    private void acquire(String context) throws DataSourceException {
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED, SecEdgarResponse.SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SecEdgarResponse.SOURCE_NAME,
                    "호출 대기 중 인터럽트됐다. " + context, e);
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            // 선택 칸(보고 기준일 · 페이지 끝)만 이 길로 온다. 제출일은 호출부가 null을 MALFORMED로 세운다.
            return null;
        }
    }
}
