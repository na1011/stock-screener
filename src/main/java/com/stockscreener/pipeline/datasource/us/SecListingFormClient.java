package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import com.stockscreener.pipeline.datasource.us.SecDailyIndexClient.IndexLine;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFactReader;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상장 판정의 SEC 원자료 받기 — 창 안의 거래소 양식 줄 · 양식 본문 · 사건 전 마지막 정기 공시의 표지(탐색 {@code US-PAST-LISTING-IMPL-1018.md}
 * U1). 해석은 {@code listing} 패키지의 순수 판독기가 한다.
 *
 * <p><b>판 범위 밖의 새 파일이다</b> — {@code ReaderVersionPinTest.SOURCE_SCOPE}의 파일은 고치지 않고 부르기만 한다(색인 목록 ·
 * 폴더 색인 · 인스턴스 · 공시 목록 · 받기 재시도). 리미터 · User-Agent는 SEC 전체와 같다.
 *
 * <p><b>창의 양식 줄은 일일 색인으로 모은다 — 분기 전체 색인({@code full-index/form.idx})을 쓰지 않는다.</b> 분기 파일은 목록이 적은
 * 크기가 받은 바이트와 다르다(2026-10-18 · 2025 QTR4 목록 41,894KB · 받은 43,155,988바이트) — 끊긴 전송을 가를 수단이 없다. 일일
 * 색인은 크기가 맞고({@link SecDailyIndexClient#formIndex}) 그 검증이 이미 서 있다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecListingFormClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecListingFormClient.class);

    /**
     * HTML 응답 끝에 SEC 가장자리가 끼우는 스크립트 하나 — 폴더 색인의 크기와 받은 길이가 이것만큼 다르다(2026-10-18 고정물 HTML 8/8이
     * 112바이트 · 빼면 8/8 크기 일치 · XML과 작은 HTML 13/13은 그대로 일치). 이것을 뺀 길이로 끊긴 전송을 가른다.
     */
    private static final Pattern INJECTED = Pattern.compile(
            "<script type=\"text/javascript\"  src=\"/[A-Za-z0-9/]+\"></script>(?=\\s*</(?:BODY|body)>|\\s*</(?:HTML|html)>)");
    /** 저장 문서의 SGML 머리 — 주 문서가 무슨 양식인지 원장이 적는다. */
    private static final Pattern TYPE_LINE = Pattern.compile("\\A\\s*<DOCUMENT>\\s*<TYPE>([^\\r\\n<]+)");
    /** 표지를 찾아 거슬러 오를 정기 공시의 상한 — 6-K처럼 XBRL 표지가 없는 공시가 끼면 다음 것으로 간다. */
    private static final int COVER_ATTEMPTS = 3;

    private final SecDailyIndexClient dailyIndex;
    private final SecFilingClient filings;
    private final RestClient files;
    private final RateLimiter rateLimiter;
    private final SecFilingClient.Backoff backoff;

    @Autowired
    public SecListingFormClient(SecDailyIndexClient dailyIndex, SecFilingClient filings,
                                ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                                @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter) {
        this(dailyIndex, filings, clientFactory, properties, rateLimiter, properties.getSecEdgarFilesBaseUrl(),
                SecFilingClient.Backoff.standard());
    }

    SecListingFormClient(SecDailyIndexClient dailyIndex, SecFilingClient filings,
                         ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                         RateLimiter rateLimiter, String filesBaseUrl, SecFilingClient.Backoff backoff) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.dailyIndex = dailyIndex;
        this.filings = filings;
        this.files = clientFactory.create(source, filesBaseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.backoff = backoff;
    }

    /**
     * [{@code from}, {@code to}] 날짜 색인의 그 양식 줄 전부. 분기 목록이 있는 날만 받는다(없는 날은 색인이 없다 —
     * {@link SecDailyIndexClient}). 실패는 던진다 — 하루를 빼고 이으면 그날 사건이 조용히 빠진다.
     */
    public List<IndexLine> lines(LocalDate from, LocalDate to, Set<String> forms) throws DataSourceException {
        List<IndexLine> out = new ArrayList<>();
        for (LocalDate q = from.withDayOfMonth(1).withMonth(((from.getMonthValue() - 1) / 3) * 3 + 1);
             !q.isAfter(to); q = q.plusMonths(3)) {
            SecDailyIndexClient.QuarterListing listing = dailyIndex.quarter(q.getYear(), (q.getMonthValue() - 1) / 3 + 1);
            for (var day : listing.formIndexKb().entrySet()) {
                if (day.getKey().isBefore(from) || day.getKey().isAfter(to)) {
                    continue;
                }
                for (IndexLine line : dailyIndex.formIndex(day.getKey(), day.getValue())) {
                    if (forms.contains(line.form())) {
                        out.add(line);
                    }
                }
            }
        }
        return out;
    }

    /**
     * {@link #lines}와 같되 날짜 색인의 크기 검증을 안 건다({@link SecDailyIndexClient#formIndexLenient}) — 후보만 만드는 쓰임(등록인 이름
     * 색인)이 목록과 안 맞는 하루에 막히지 않게. 크기가 어긋난 날은 로그로 남긴다.
     */
    public List<IndexLine> candidateLines(LocalDate from, LocalDate to, Set<String> forms) throws DataSourceException {
        List<IndexLine> out = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (LocalDate q = from.withDayOfMonth(1).withMonth(((from.getMonthValue() - 1) / 3) * 3 + 1);
             !q.isAfter(to); q = q.plusMonths(3)) {
            SecDailyIndexClient.QuarterListing listing = dailyIndex.quarter(q.getYear(), (q.getMonthValue() - 1) / 3 + 1);
            for (var day : listing.formIndexKb().entrySet()) {
                if (day.getKey().isBefore(from) || day.getKey().isAfter(to)) {
                    continue;
                }
                SecDailyIndexClient.Lenient got = dailyIndex.formIndexLenient(day.getKey(), day.getValue());
                got.sizeNote().ifPresent(note -> mismatched.add(day.getKey() + " " + note));
                for (IndexLine line : got.lines()) {
                    if (forms.contains(line.form())) {
                        out.add(line);
                    }
                }
            }
        }
        if (!mismatched.isEmpty()) {
            log.warn("후보 색인 중 목록 크기와 다른 날 {}개 — 줄 해석은 통과했다(후보 생성에만 쓴다): {}", mismatched.size(), mismatched);
        }
        return out;
    }

    /**
     * 양식의 주 문서. {@code 25-NSE}는 {@code primary_doc.xml}이고, 나머지는 SGML 머리의 {@code <TYPE>}이 그 양식인 HTML이다
     * (폴더 항목은 이름순이라 순서로 못 고른다 — 후보가 하나면 그것, 여럿이면 머리를 읽는다).
     *
     * @return 주 문서를 못 고르면 비었다(호출부가 그 양식을 「못 읽음」으로 센다)
     */
    public Optional<byte[]> document(long cik, String accession, String form) throws DataSourceException {
        List<SecFilingClient.FolderItem> items = filings.folder(cik, accession);
        if ("25-NSE".equals(form)) {
            for (SecFilingClient.FolderItem item : items) {
                if ("primary_doc.xml".equals(item.name())) {
                    return Optional.of(get(cik, accession, item));
                }
            }
            return Optional.empty();
        }
        List<SecFilingClient.FolderItem> html = items.stream()
                .filter(i -> i.name().toLowerCase(java.util.Locale.ROOT).matches(".*\\.html?$"))
                .filter(i -> !i.name().contains("-index"))
                .toList();
        for (SecFilingClient.FolderItem item : html) {
            byte[] body = get(cik, accession, item);
            if (html.size() == 1 || form.equals(typeOf(body))) {
                return Optional.of(body);
            }
        }
        return Optional.empty();
    }

    /**
     * {@code before} 전(그날 제외)에 낸 마지막 정기 공시 중 XBRL 표지가 있는 것의 표지. 공시 목록을 한 번 받고, 최근 것부터
     * {@link #COVER_ATTEMPTS}건까지 거슬러 오른다.
     *
     * @param since 공시 목록을 거슬러 받을 하한(그보다 앞 공시는 안 본다)
     */
    public Optional<Cover> lastCoverBefore(long cik, LocalDate before, LocalDate since) throws DataSourceException {
        List<SecFilingClient.Filing> periodic = filings.filings(cik, since).stream()
                .filter(f -> SecEdgarFacts.PERIODIC_FORMS.contains(f.form()) && f.xbrl() && f.filed().isBefore(before))
                .sorted(Comparator.comparing(SecFilingClient.Filing::filed).reversed())
                .limit(COVER_ATTEMPTS)
                .toList();
        for (SecFilingClient.Filing filing : periodic) {
            List<SecFilingClient.FolderItem> items = filings.folder(cik, filing.accession());
            String context = "listing cover %d/%s".formatted(cik, filing.accession());
            String picked;
            try {
                picked = SecFilingSupplement.pickInstance(items.stream().map(SecFilingClient.FolderItem::name).toList(), context);
            } catch (SecFilingSupplement.UnreadableFiling e) {
                continue;
            }
            if (picked == null) {
                continue;
            }
            SecFilingClient.FolderItem item = items.stream().filter(i -> i.name().equals(picked)).findFirst().orElseThrow();
            byte[] xml = filings.instance(cik, filing.accession(), item);
            ClassFacts facts = ClassFactReader.readListingCover(new ByteArrayInputStream(xml), cik, context);
            if (!facts.coverOf(ClassFactReader.TRADING_SYMBOL).isEmpty()) {
                return Optional.of(new Cover(filing.accession(), filing.form(), filing.filed(), facts));
            }
        }
        return Optional.empty();
    }

    /** 사건 전 마지막 정기 공시의 표지 — 구성원마다 기호 · 거래소 코드 · 증권 이름({@link ClassFacts#coverOf}). */
    public record Cover(String accession, String form, LocalDate filed, ClassFacts facts) {
    }

    /** 끼운 스크립트를 뺀 길이가 색인 크기와 같아야 한다 — 다르면 끊긴 전송이거나 오류 본문이라 다시 받는다. */
    private byte[] get(long cik, String accession, SecFilingClient.FolderItem item) throws DataSourceException {
        String folder = accession.replace("-", "");
        String context = "listing form %d/%s/%s".formatted(cik, folder, item.name());
        long expected = item.expectedSize(context);
        ResponseEntity<byte[]> response = SecFilingClient.fetch(rateLimiter, backoff, () -> files.get()
                .uri("/Archives/edgar/data/{cik}/{folder}/{name}", cik, folder, item.name())
                .retrieve().toEntity(byte[].class), received -> sizeMismatch(received.getBody(), expected), context);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new DataSourceException(SecEdgarResponse.kindOf(response.getStatusCode().value()),
                    SecEdgarResponse.SOURCE_NAME, "HTTP %d. %s".formatted(response.getStatusCode().value(), context));
        }
        return stripInjected(response.getBody());
    }

    static String sizeMismatch(byte[] body, long expected) {
        if (body == null || body.length == 0) {
            return "200인데 본문이 없다.";
        }
        long stripped = stripInjected(body).length;
        return stripped == expected ? null
                : "받은 길이 %d(끼운 스크립트를 빼면 %d)가 색인 크기 %d와 다르다 — 받다 만 본문이거나 오류 본문이다."
                .formatted(body.length, stripped, expected);
    }

    static byte[] stripInjected(byte[] body) {
        String text = new String(body, StandardCharsets.ISO_8859_1);
        Matcher m = INJECTED.matcher(text);
        if (!m.find()) {
            return body;
        }
        return (text.substring(0, m.start()) + text.substring(m.end())).getBytes(StandardCharsets.ISO_8859_1);
    }

    static String typeOf(byte[] body) {
        Matcher m = TYPE_LINE.matcher(new String(body, 0, Math.min(body.length, 200), StandardCharsets.ISO_8859_1));
        return m.find() ? m.group(1).strip() : null;
    }
}
