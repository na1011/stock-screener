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

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 등록인의 수시 보고서(8-K · 8-K/A)와 그 항목을 submissions({@code data.sec.gov/submissions/CIK##########.json})로 받는다(ADR-0195 ·
 * {@code docs/api-spec/sec-edgar-submissions-items.md}). 실적 발표는 항목에 {@code 2.02}(Results of Operations and Financial
 * Condition)가 있는 8-K다 — 일일 색인에는 항목이 없다.
 *
 * <p>{@code filings.recent}는 「최근 1년 또는 1,000건 중 많은 쪽」이고 그 앞은 {@code filings.files} 페이지다(2026-10-30 실측: C · WFC ·
 * GS의 recent 첫 날이 2025-10-30 · 그 앞은 페이지 1~3). {@code since}에 닿을 때까지 페이지를 따른다.
 *
 * <p><b>판 범위 밖의 새 파일이다</b> — {@link SecFilingClient}가 같은 submissions를 받지만 그 파일 전체가
 * {@code ReaderVersionPinTest.SOURCE_SCOPE}이고(고치면 판을 올려 저장 행을 다시 받는다), 그 {@code Filing}에는 항목 열이 없다. 받기
 * ({@link SecFilingClient#fetch})와 상태 판정({@link SecEdgarResponse})을 부르기만 한다 — {@link SecDailyIndexClient}와 같은 꼴이다.
 * 페이지 따르기는 {@link SecFilingClient#filings}와 같은 규칙이다(끝이 {@code since}보다 앞인 페이지는 건너뛴다).
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecCurrentReportClient {

    /** 실적 발표를 싣는 폼. */
    static final List<String> FORMS = List.of("8-K", "8-K/A");

    private final RestClient data;
    private final RateLimiter rateLimiter;
    private final SecFilingClient.Backoff backoff;

    @Autowired
    public SecCurrentReportClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                                  @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter, properties.getSecEdgar().getBaseUrl(), SecFilingClient.Backoff.standard());
    }

    SecCurrentReportClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties, RateLimiter rateLimiter,
                           String dataBaseUrl, SecFilingClient.Backoff backoff) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.data = clientFactory.create(source, dataBaseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.backoff = backoff;
    }

    /**
     * 수시 보고서 하나. {@code items}는 원장이 적은 그대로(「2.02,9.01」) — 비어 있을 수 있다. {@code accepted}는 접수 시각
     * ({@code acceptanceDateTime} · UTC) — 실적 발표가 그날 종가 전에 공개됐는지를 가른다(ADR-0195). 못 읽으면 {@code null}이다.
     */
    public record CurrentReport(String accession, String form, LocalDate filed, String items, Instant accepted) {

        public CurrentReport(String accession, String form, LocalDate filed, String items) {
            this(accession, form, filed, items, null);
        }

        /** 실적 발표인가 — 항목 목록에 {@code 2.02}가 있다. */
        public boolean isEarningsRelease() {
            return isEarningsRelease(items);
        }

        public static boolean isEarningsRelease(String items) {
            if (items == null) {
                return false;
            }
            for (String item : items.split(",")) {
                if (item.strip().equals("2.02")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code since} 이후 제출된 8-K · 8-K/A 전부. */
    public List<CurrentReport> currentReports(long cik, LocalDate since) throws DataSourceException {
        String padded = SecTickerCikCache.padded(cik);
        String context = "submissions items " + padded;
        JsonNode filings = json("/submissions/" + padded + ".json", context).path("filings");
        if (!filings.has("recent")) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "200인데 filings.recent가 없다. " + context);
        }
        List<CurrentReport> out = new ArrayList<>(parse(filings.path("recent"), since, context));
        for (JsonNode page : filings.path("files")) {
            String name = page.path("name").asString("");
            LocalDate to = parseDate(page.path("filingTo").asString(""));
            if (name.isBlank() || (to != null && to.isBefore(since))) {
                continue;
            }
            out.addAll(parse(json("/submissions/" + name, context + " " + name), since, context + " " + name));
        }
        return out;
    }

    /**
     * 열 배열에서 8-K · 8-K/A만. <b>열 길이가 다르면 본문 이상이다</b> — 항목 열이 짧으면 접수번호와 항목이 어긋나 남의 항목이
     * 붙는다.
     */
    static List<CurrentReport> parse(JsonNode table, LocalDate since, String context) throws DataSourceException {
        JsonNode accessions = table.path("accessionNumber");
        JsonNode forms = table.path("form");
        JsonNode filed = table.path("filingDate");
        JsonNode items = table.path("items");
        JsonNode accepted = table.path("acceptanceDateTime");
        int n = accessions.size();
        if (forms.size() != n || filed.size() != n || items.size() != n) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "열 배열의 길이가 다르다(접수번호 %d · 폼 %d · 제출일 %d · 항목 %d). %s"
                            .formatted(n, forms.size(), filed.size(), items.size(), context));
        }
        List<CurrentReport> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String form = forms.get(i).asString("");
            if (!FORMS.contains(form)) {
                continue;
            }
            LocalDate date = parseDate(filed.get(i).asString(""));
            if (date == null) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "제출일이 날짜가 아니다: %s %s".formatted(filed.get(i), context));
            }
            if (date.isBefore(since)) {
                continue;
            }
            out.add(new CurrentReport(accessions.get(i).asString(""), form, date, items.get(i).asString(""),
                    i < accepted.size() ? parseInstant(accepted.get(i).asString(""), context) : null));
        }
        return out;
    }

    private JsonNode json(String path, String context) throws DataSourceException {
        ResponseEntity<String> response = SecFilingClient.fetch(rateLimiter, backoff,
                () -> data.get().uri(path).retrieve().toEntity(String.class),
                received -> SecEdgarResponse.unreadableJson(received.getBody()), context);
        return SecEdgarResponse.requireJson(response.getStatusCode(), response.getBody(), context);
    }

    /**
     * 「2026-10-29T20:05:45.000Z」(진짜 UTC — SEC 데이터셋의 동부 시각 {@code accepted}와 8-K 22건이 일치 · ADR-0204). 칸이 비면 {@code null}
     * (원장이 시각을 안 줬다 — 그 줄의 공개일은 접수일로 보수적으로 둔다). 값이 있는데 못 읽으면 본문 이상이다 — 조용히 「모름」으로 접지 않는다.
     */
    private static Instant parseInstant(String raw, String context) throws DataSourceException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "접수 시각을 못 읽었다: %s %s".formatted(raw, context), e);
        }
    }

    /**
     * 발행사의 <b>전 이력</b> 공시의 접수 시각(접수번호 → 시각 · 양식 무관 · ADR-0204) — 미국 판이 보이기 시작하는 날의 재료. 창으로 자르지
     * 않는다(창 밖 비교열 공시의 시각도 그 판의 공개 시각이다). 시각 칸이 빈 공시는 결과에 없다(모른다). 받기 실패는 던진다(그 종목의 실패).
     */
    public java.util.Map<String, Instant> acceptances(long cik) throws DataSourceException {
        String padded = SecTickerCikCache.padded(cik);
        String context = "submissions acceptance " + padded;
        JsonNode filings = json("/submissions/" + padded + ".json", context).path("filings");
        if (!filings.has("recent")) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "200인데 filings.recent가 없다. " + context);
        }
        java.util.Map<String, Instant> out = new java.util.HashMap<>();
        acceptances(filings.path("recent"), out, context);
        for (JsonNode page : filings.path("files")) {
            String name = page.path("name").asString("");
            if (!name.isBlank()) {
                acceptances(json("/submissions/" + name, context + " " + name), out, context + " " + name);
            }
        }
        return out;
    }

    static void acceptances(JsonNode table, java.util.Map<String, Instant> out, String context) throws DataSourceException {
        JsonNode accessions = table.path("accessionNumber");
        JsonNode accepted = table.path("acceptanceDateTime");
        if (accepted.size() != accessions.size()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "열 배열의 길이가 다르다(접수번호 %d · 접수 시각 %d). %s".formatted(accessions.size(), accepted.size(), context));
        }
        for (int i = 0; i < accessions.size(); i++) {
            Instant at = parseInstant(accepted.get(i).asString(""), context);
            if (at != null) {
                out.put(accessions.get(i).asString(""), at);
            }
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            // 선택 칸(페이지 끝)만 이 길로 온다. 제출일은 호출부가 null을 MALFORMED로 세운다.
            return null;
        }
    }
}
