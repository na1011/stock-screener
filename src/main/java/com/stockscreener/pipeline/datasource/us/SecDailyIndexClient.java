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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EDGAR 일일 색인({@code /Archives/edgar/daily-index/})을 받는다 — 그날 제출된 공시 전부의 목록이다(ADR-0176 ·
 * {@code docs/api-spec/sec-edgar-daily-index.md}).
 *
 * <p><b>색인이 없는 날(주말·연방 공휴일)은 404가 아니라 403이다</b>(2026-10-12 실측 · 대조군 같은 주의 평일 200). 상태코드로는
 * 「없는 날」과 「막힘」을 못 가르므로 날짜마다 묻지 않고, 분기 디렉터리 목록({@code index.json})이 <b>있는 날만</b> 나열한 것을
 * 따른다({@link #quarter}). 판정(목록의 마지막 날보다 앞인데 없는 날 = 색인 없음)은 호출부가 한다.
 *
 * <p><b>판 범위 밖의 새 파일이다</b> — {@code ReaderVersionPinTest.SOURCE_SCOPE}의 일곱 파일은 고치지 않고, 받기
 * ({@link SecFilingClient#fetch})와 상태 판정({@link SecEdgarResponse})을 부르기만 한다. 리미터·User-Agent는 SEC 전체와 같다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecDailyIndexClient implements SecFilingListSource.DailyIndex {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern FORM_FILE = Pattern.compile("form\\.(\\d{8})\\.idx");
    private static final Pattern KB = Pattern.compile("(\\d+) KB");
    /**
     * 색인 한 줄: 폼 · 회사 이름 · CIK · 제출일 · 파일 경로. 폼(「SC 13D」)과 회사 이름에 공백이 끼므로 오른쪽 셋(CIK · 날짜 ·
     * 경로)을 고정하고, 폼은 첫 「두 칸 이상 공백」 앞까지다.
     */
    private static final Pattern LINE = Pattern.compile(
            "^(\\S(?:.*?\\S)?)\\s{2,}(.*?)\\s+(\\d+)\\s+(\\d{8})\\s+edgar/data/(\\d+)/(\\d{10}-\\d{2}-\\d{6})\\.txt\\s*$");

    private final RestClient files;
    private final RateLimiter rateLimiter;
    private final SecFilingClient.Backoff backoff;

    @Autowired
    public SecDailyIndexClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                               @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter) {
        this(clientFactory, properties, rateLimiter, properties.getSecEdgarFilesBaseUrl(),
                SecFilingClient.Backoff.standard());
    }

    SecDailyIndexClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                        RateLimiter rateLimiter, String filesBaseUrl, SecFilingClient.Backoff backoff) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.files = clientFactory.create(source, filesBaseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.backoff = backoff;
    }

    /** 분기 디렉터리에 올라온 일일 폼 색인 — 날짜와 원장이 적은 크기(KB 올림). */
    public record QuarterListing(int year, int quarter, NavigableMap<LocalDate, Integer> formIndexKb) {

        /** 목록의 마지막 날. 비었으면 {@code null} — 분기 첫 색인이 아직 안 올라왔다. */
        public LocalDate lastDay() {
            return formIndexKb.isEmpty() ? null : formIndexKb.lastKey();
        }
    }

    /** 색인의 한 줄 — 정기 공시인지는 호출부가 거른다. {@code filed}는 원장의 제출일이다(색인 날짜보다 앞일 수 있다). */
    public record IndexLine(String form, long cik, LocalDate filed, String accession) {
    }

    /**
     * 그 분기의 일일 색인 목록. 실패(403 포함)는 던진다 — 목록 없이 날을 닫으면 색인이 있는 날을 「없는 날」로 넘긴다.
     */
    @Override
    public QuarterListing quarter(int year, int quarter) throws DataSourceException {
        String path = "/Archives/edgar/daily-index/%d/QTR%d/index.json".formatted(year, quarter);
        String context = "daily-index " + year + " QTR" + quarter;
        ResponseEntity<String> response = SecFilingClient.fetch(rateLimiter, backoff,
                () -> files.get().uri(path).retrieve().toEntity(String.class),
                received -> SecEdgarResponse.unreadableJson(received.getBody()), context);
        JsonNode root = SecEdgarResponse.requireJson(response.getStatusCode(), response.getBody(), context);
        return parseQuarter(year, quarter, root, context);
    }

    /**
     * 그날의 폼 색인 줄 전부. {@code expectedKb}는 분기 목록이 적은 크기 — 받은 길이가 그 KB 올림과 다르면 끊긴 전송이다
     * (2026-10-12 저장분 7일 전부 {@code ceil(바이트/1024)} = 적힌 KB).
     */
    @Override
    public List<IndexLine> formIndex(LocalDate day, int expectedKb) throws DataSourceException {
        int quarter = (day.getMonthValue() - 1) / 3 + 1;
        String path = "/Archives/edgar/daily-index/%d/QTR%d/form.%s.idx".formatted(day.getYear(), quarter, BASIC.format(day));
        String context = "form index " + day;
        ResponseEntity<byte[]> response = SecFilingClient.fetch(rateLimiter, backoff,
                () -> files.get().uri(path).retrieve().toEntity(byte[].class),
                received -> sizeMismatch(received.getBody(), expectedKb), context);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new DataSourceException(SecEdgarResponse.kindOf(response.getStatusCode().value()),
                    SecEdgarResponse.SOURCE_NAME, "HTTP %d. %s".formatted(response.getStatusCode().value(), context));
        }
        return parseFormIndex(new String(response.getBody(), StandardCharsets.ISO_8859_1), day, context);
    }

    static String sizeMismatch(byte[] body, int expectedKb) {
        if (body == null || body.length == 0) {
            return "200인데 본문이 없다.";
        }
        long kb = (body.length + 1023L) / 1024L;
        return kb == expectedKb ? null
                : "받은 %d바이트(%dKB)가 목록의 %dKB와 다르다 — 받다 만 본문이거나 오류 본문이다.".formatted(body.length, kb, expectedKb);
    }

    /** 분기 목록 JSON에서 {@code form.yyyymmdd.idx} 항목만 날짜순으로 낸다. 크기를 못 읽는 항목은 본문 이상이다. */
    static QuarterListing parseQuarter(int year, int quarter, JsonNode root, String context) throws DataSourceException {
        JsonNode items = root.path("directory").path("item");
        if (!items.isArray()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "분기 색인 목록에 directory.item이 없다. " + context);
        }
        TreeMap<LocalDate, Integer> out = new TreeMap<>();
        for (JsonNode item : items) {
            Matcher name = FORM_FILE.matcher(item.path("name").asString(""));
            if (!name.matches()) {
                continue;
            }
            LocalDate day = LocalDate.parse(name.group(1), BASIC);
            Matcher size = KB.matcher(item.path("size").asString("").trim());
            if (!size.matches()) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "폼 색인의 크기를 못 읽었다: " + item.path("size").asString("") + " (" + day + "). " + context);
            }
            out.put(day, Integer.parseInt(size.group(1)));
        }
        return new QuarterListing(year, quarter, out);
    }

    /**
     * 폼 색인 본문을 줄로 읽는다. 머리(구분선 {@code ---} 앞)는 건너뛰고, 그 뒤 빈 줄이 아닌데 꼴이 안 맞는 줄은 본문 이상이다
     * — 조용히 건너뛰면 그 공시가 장부에서 빠진다. 줄의 제출일은 원장의 것 그대로 낸다(색인 날짜보다 앞일 수 있다).
     */
    static List<IndexLine> parseFormIndex(String text, LocalDate day, String context) throws DataSourceException {
        List<IndexLine> out = new ArrayList<>();
        boolean body = false;
        for (String raw : text.split("\r?\n")) {
            if (!body) {
                body = raw.startsWith("-----");
                continue;
            }
            if (raw.isBlank()) {
                continue;
            }
            Matcher m = LINE.matcher(raw);
            if (!m.matches()) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "폼 색인 줄의 꼴이 다르다: 「" + raw.strip() + "」. " + context);
            }
            LocalDate filed;
            try {
                filed = LocalDate.parse(m.group(4), BASIC);
            } catch (DateTimeParseException e) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "폼 색인 줄의 제출일을 못 읽었다: 「" + raw.strip() + "」. " + context, e);
            }
            if (!m.group(3).equals(m.group(5))) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "폼 색인 줄의 CIK 칸과 경로의 CIK가 다르다: 「" + raw.strip() + "」. " + context);
            }
            // 늦게 배포된 공시는 원래 제출일을 단 채 뒷날 색인에만 실린다(2026-10 저장분 7일 · 하루 19~55줄 · 앞날 색인과 겹침 0) —
            // 버리면 그 공시가 어느 날 목록에도 없다. 색인 날짜보다 뒤인 제출일만 본문 이상이다.
            if (filed.isAfter(day)) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                        "폼 색인 줄의 제출일 %s가 색인 날짜보다 뒤다. %s".formatted(filed, context));
            }
            out.add(new IndexLine(m.group(1), Long.parseLong(m.group(3)), filed, m.group(6)));
        }
        if (!body) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "폼 색인에 구분선이 없다 — 머리만 왔거나 다른 본문이다. " + context);
        }
        return out;
    }
}
