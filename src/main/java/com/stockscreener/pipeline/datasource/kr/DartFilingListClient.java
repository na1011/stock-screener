package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * DART 공시검색({@code list.json})으로 그날 접수된 정기보고서와 실적 발표가 실리는 공시 목록을 받는다(ADR-0176 · ADR-0195 ·
 * {@code docs/api-spec/dart-filing-list.md}).
 *
 * <p>보고서 종류는 <b>공시 상세유형</b>({@code pblntf_detail_ty})으로 거른다 — A001 사업 · A002 반기 · A003 분기. 이름 문자열로
 * 가르지 않는다: 정정({@code [기재정정]})과 첨부추가({@code [첨부추가]})도 그 유형으로 걸리고 각자 접수번호를 갖는다(2026-10-12 실측).
 * 시장은 {@code corp_cls} Y(유가)·K(코스닥)다.
 *
 * <p><b>{@code 013}은 그날 그 조합에 공시가 없다는 답이다</b>(공휴일 10-09 실측) — 실패가 아니다. 그 밖의 오류는 던진다 — 하루의
 * 목록을 반만 받고 그날을 닫으면 나머지 공시가 장부에서 영영 빠진다.
 */
@ConditionalOnApiKey("dart")
@Component
public class DartFilingListClient {

    /** 요청 경로 — 일일 총량을 이 경로로 센다(ADR-0176). */
    private static final String PATH = "/list.json";
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    /** 한 페이지 최대 건수(개발가이드 1~100). */
    static final int PAGE_COUNT = 100;
    /** 정기보고서 세 종류. */
    public static final List<String> DETAIL_TYPES = List.of("A001", "A002", "A003");
    /**
     * 실적 발표가 실리는 두 종류(ADR-0195) — I001 수시공시(매출액또는손익구조 변동) · I002 공정공시(영업(잠정)실적). 둘 다 실적이 아닌
     * 공시도 싣는다 — 실적 발표인지는 {@link DartEarningsReleaseNames}가 이름으로 가른다(상세유형이 더 잘게 나뉘지 않는다).
     */
    public static final List<String> RELEASE_DETAIL_TYPES = List.of("I001", "I002");
    /** 하루 목록이 묻는 종류 — 정기보고서 뒤에 실적 발표 둘. */
    private static final List<String> DAY_DETAIL_TYPES =
            Stream.concat(DETAIL_TYPES.stream(), RELEASE_DETAIL_TYPES.stream()).toList();
    /** 유가증권 · 코스닥. 우리 국내 편입이 사는 두 시장이다. */
    static final List<String> CORP_CLASSES = List.of("Y", "K");

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartDailyQuota dailyQuota;
    private final String apiKey;

    public DartFilingListClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                                @Qualifier("dartRateLimiter") RateLimiter rateLimiter,
                                DartDailyQuota dailyQuota) {
        DataSourceProperties.Source source = properties.getDart();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.dailyQuota = dailyQuota;
        this.apiKey = source.getApiKey();
    }

    /** 목록의 한 줄. {@code detailType}은 우리가 물은 상세유형이다(응답에는 없다). */
    public record ListRow(String detailType, String corpCode, String corpName, String stockCode, String corpCls,
                          String reportName, String rceptNo, LocalDate rceptDate) {
    }

    /**
     * 그날의 목록. {@code answered}가 거짓이면 모든 조합이 {@code 013}이었다(공시 없음 — 공휴일·주말).
     */
    public record DayListing(LocalDate day, boolean answered, List<ListRow> rows) {
    }

    /**
     * 그날 접수된 다섯 종류(정기보고서 셋 · 실적 발표가 실리는 둘) × 두 시장의 목록을 페이지 끝까지 받는다. 하나라도 실패하면 던진다 —
     * 실적 발표 줄도 그날을 닫는 재료다(닫은 날의 발표 이력이 빠지면 화면이 「발표 없음」을 거짓으로 말한다).
     */
    public DayListing day(LocalDate day) throws DataSourceException {
        requireKey();
        List<ListRow> rows = new ArrayList<>();
        boolean answered = false;
        for (String detailType : DAY_DETAIL_TYPES) {
            for (String corpCls : CORP_CLASSES) {
                Page first = page(day, detailType, corpCls, 1);
                if (first == null) {
                    continue; // 013 — 그 조합은 그날 공시가 없다.
                }
                answered = true;
                List<ListRow> combo = new ArrayList<>(first.rows());
                for (int pageNo = 2; pageNo <= first.totalPage(); pageNo++) {
                    Page next = page(day, detailType, corpCls, pageNo);
                    if (next == null) {
                        throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                                "첫 페이지가 %d쪽이라 했는데 %d쪽이 013이다. %s".formatted(first.totalPage(), pageNo,
                                        context(day, detailType, corpCls, pageNo)));
                    }
                    combo.addAll(next.rows());
                }
                if (combo.size() != first.totalCount()) {
                    // 페이지 사이에 공시가 접수되면 뒤로 밀린 줄이 빠지거나 겹친다 — 그날을 닫지 않고 다음 실행이 다시 받는다.
                    throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
                            "받은 줄 %d가 total_count %d와 다르다. %s".formatted(combo.size(), first.totalCount(),
                                    context(day, detailType, corpCls, 0)));
                }
                rows.addAll(combo);
            }
        }
        return new DayListing(day, answered, rows);
    }

    /**
     * 한 회사의 실적 발표가 실리는 두 종류 목록을 {@code from}~{@code to} 접수분 전부 받는다(ADR-0195 과거 훑기). 회사 코드를 주면
     * 기간 제한이 없다(2026-10-30 실측: 삼성전자 2025-07-01~2026-10-30 I002 24건 · I001 37건이 한 콜). {@code 013}은 그 종류에 공시가
     * 없다는 답이다. 페이지 사이에 접수돼 줄 수가 {@code total_count}와 다르면 던진다 — 반만 받은 이력을 「훑었다」로 남기지 않는다.
     */
    public List<ListRow> releases(String corpCode, LocalDate from, LocalDate to) throws DataSourceException {
        requireKey();
        List<ListRow> rows = new ArrayList<>();
        for (String detailType : RELEASE_DETAIL_TYPES) {
            String context = "list %s %s %s~%s".formatted(corpCode, detailType, from, to);
            Page first = fetch(detailType, from, to, Map.of("corp_code", corpCode), 1, context);
            if (first == null) {
                continue;
            }
            List<ListRow> combo = new ArrayList<>(first.rows());
            for (int pageNo = 2; pageNo <= first.totalPage(); pageNo++) {
                Page next = fetch(detailType, from, to, Map.of("corp_code", corpCode), pageNo, context + " page=" + pageNo);
                if (next == null) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                            "첫 페이지가 %d쪽이라 했는데 %d쪽이 013이다. %s".formatted(first.totalPage(), pageNo, context));
                }
                combo.addAll(next.rows());
            }
            if (combo.size() != first.totalCount()) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
                        "받은 줄 %d가 total_count %d와 다르다. %s".formatted(combo.size(), first.totalCount(), context));
            }
            rows.addAll(combo);
        }
        return rows;
    }

    private void requireKey() throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
    }

    /** 하루 · 한 종류 · 한 시장의 한 페이지. {@code 013}이면 {@code null}. */
    private Page page(LocalDate day, String detailType, String corpCls, int pageNo) throws DataSourceException {
        return fetch(detailType, day, day, Map.of("corp_cls", corpCls), pageNo, context(day, detailType, corpCls, pageNo));
    }

    /** 한 페이지. 접수일이 {@code from}~{@code to} 밖인 줄은 본문 이상이다. {@code 013}이면 {@code null}. */
    private Page fetch(String detailType, LocalDate from, LocalDate to, Map<String, String> filter, int pageNo, String context)
            throws DataSourceException {
        DartResponse.acquire(rateLimiter, dailyQuota, PATH, context);
        ResponseEntity<byte[]> response;
        try {
            response = client.get().uri(uri -> {
                        uri.path(PATH)
                                .queryParam("crtfc_key", apiKey)
                                .queryParam("bgn_de", BASIC.format(from))
                                .queryParam("end_de", BASIC.format(to))
                                .queryParam("pblntf_detail_ty", detailType);
                        filter.forEach(uri::queryParam);
                        return uri.queryParam("page_no", pageNo)
                                .queryParam("page_count", PAGE_COUNT)
                                .build();
                    })
                    .retrieve()
                    // 본문을 바이트로 받는다 — 상태를 먼저 보고 UTF-8로 판다(DartResponse.read · 문자열로 받으면 한글이 깨진다).
                    .toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }
        try {
            return parsePage(DartResponse.read(response, context), from, to, detailType, context);
        } catch (DataSourceException e) {
            if (DartResponse.isNoData(e)) {
                return null;
            }
            throw e;
        }
    }

    record Page(int totalCount, int totalPage, List<ListRow> rows) {
    }

    /**
     * 한 페이지 본문을 읽는다. {@code 013}은 {@link DartResponse#requireList}가 던지는 「없음」이다. 접수일이 그날이 아니거나
     * 접수번호가 14자리가 아닌 줄은 본문 이상이다 — 다른 날의 공시를 그날로 닫지 않는다.
     */
    static Page parsePage(JsonNode root, LocalDate day, String detailType, String context) throws DataSourceException {
        return parsePage(root, day, day, detailType, context);
    }

    static Page parsePage(JsonNode root, LocalDate from, LocalDate to, String detailType, String context)
            throws DataSourceException {
        JsonNode list = DartResponse.requireList(root, context);
        int totalCount = root.path("total_count").asInt(-1);
        int totalPage = root.path("total_page").asInt(-1);
        if (totalCount < 0 || totalPage < 1) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                    "total_count·total_page가 없다. " + context);
        }
        List<ListRow> rows = new ArrayList<>();
        for (JsonNode node : list) {
            String rceptNo = node.path("rcept_no").asString("").strip();
            String rceptDt = node.path("rcept_dt").asString("").strip();
            LocalDate received = rceptDt.matches("\\d{8}") ? LocalDate.parse(rceptDt, BASIC) : null;
            if (!rceptNo.matches("\\d{14}") || received == null || received.isBefore(from) || received.isAfter(to)) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                        "목록 줄의 접수번호 「%s」·접수일 「%s」가 %s~%s 꼴이 아니다. %s".formatted(rceptNo, rceptDt, from, to, context));
            }
            rows.add(new ListRow(detailType,
                    node.path("corp_code").asString("").strip(),
                    node.path("corp_name").asString("").strip(),
                    node.path("stock_code").asString("").strip(),
                    node.path("corp_cls").asString("").strip(),
                    node.path("report_nm").asString("").strip(),
                    rceptNo, received));
        }
        return new Page(totalCount, totalPage, rows);
    }

    private static String context(LocalDate day, String detailType, String corpCls, int pageNo) {
        return "list %s %s/%s page=%d".formatted(day, detailType, corpCls, pageNo);
    }
}
