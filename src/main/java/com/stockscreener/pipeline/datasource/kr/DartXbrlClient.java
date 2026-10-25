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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 국내 보통주 몫 재료(ADR-0192)의 DART 세 경로 — 종목별 정기 공시 목록({@code list.json} corp_code) · 재무제표 원본
 * ({@code fnlttXbrl.xml}) · 주식총수 명부({@code stockTotqySttus.json}). 계약은 {@code docs/api-spec/dart-xbrl.md}이고 봉투 판별은
 * {@link DartResponse}가 정본이다. 일일 총량은 경로마다 센다({@link DartDailyQuota}).
 */
@ConditionalOnApiKey("dart")
@Component
public class DartXbrlClient {

    static final String LIST_PATH = "/list.json";
    static final String XBRL_PATH = "/fnlttXbrl.xml";
    static final String SHARE_TOTALS_PATH = "/stockTotqySttus.json";
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartDailyQuota dailyQuota;
    private final String apiKey;

    public DartXbrlClient(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                          @Qualifier("dartRateLimiter") RateLimiter rateLimiter, DartDailyQuota dailyQuota) {
        DataSourceProperties.Source source = properties.getDart();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.dailyQuota = dailyQuota;
        this.apiKey = source.getApiKey();
    }

    /** 목록의 판 하나. {@code detailType}은 우리가 물은 상세유형이다(응답에는 없다 · {@code dart-filing-list.md}). */
    public record Filing(String detailType, String reportName, String rceptNo, LocalDate rceptDate) {
    }

    /**
     * 그 회사의 정기 공시(사업 · 반기 · 분기 — 상세유형마다 한 번)를 기간 끝까지 받는다. {@code corp_code}를 주면 기간 제한이 없다
     * (기아 2015-01-01~2026-10-25 58건 · 2026-10-25). 상세유형 하나라도 실패하면 던진다 — 반쯤 받은 목록으로 판을 짝지으면 빠진 판이
     * 「재료 없음」이 된다.
     */
    public List<Filing> periodicFilings(String corpCode, LocalDate from, LocalDate to) throws DataSourceException {
        requireKey();
        List<Filing> out = new ArrayList<>();
        for (String detailType : DartFilingListClient.DETAIL_TYPES) {
            int pageNo = 1;
            int totalPage = 1;
            int totalCount = -1;
            List<Filing> combo = new ArrayList<>();
            do {
                String context = "list.json corp_code=%s %s~%s %s page=%d".formatted(corpCode, from, to, detailType, pageNo);
                DartResponse.acquire(rateLimiter, dailyQuota, LIST_PATH, context);
                final int page = pageNo;
                ResponseEntity<byte[]> response = get(context, uri -> uri.path(LIST_PATH)
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bgn_de", BASIC.format(from))
                        .queryParam("end_de", BASIC.format(to))
                        .queryParam("pblntf_detail_ty", detailType)
                        .queryParam("page_no", page)
                        .queryParam("page_count", DartFilingListClient.PAGE_COUNT)
                        .build());
                JsonNode root;
                JsonNode list;
                try {
                    root = DartResponse.read(response, context);
                    list = DartResponse.requireList(root, context);
                } catch (DataSourceException e) {
                    if (DartResponse.isNoData(e) && pageNo == 1) {
                        break;   // 013 — 그 종류의 정기 공시가 그 기간에 없다
                    }
                    throw e;
                }
                totalCount = root.path("total_count").asInt(-1);
                totalPage = root.path("total_page").asInt(-1);
                if (totalCount < 0 || totalPage < 1) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                            "total_count·total_page가 없다. " + context);
                }
                for (JsonNode node : list) {
                    String rceptNo = node.path("rcept_no").asString("").strip();
                    String rceptDt = node.path("rcept_dt").asString("").strip();
                    if (!rceptNo.matches("\\d{14}") || !rceptDt.matches("\\d{8}")) {
                        throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                                "목록 줄의 접수번호 「%s」·접수일 「%s」가 꼴이 아니다. %s".formatted(rceptNo, rceptDt, context));
                    }
                    combo.add(new Filing(detailType, node.path("report_nm").asString("").strip(), rceptNo,
                            LocalDate.parse(rceptDt, BASIC)));
                }
                pageNo++;
            } while (pageNo <= totalPage);
            if (totalCount >= 0 && combo.size() != totalCount) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
                        "받은 줄 %d가 total_count %d와 다르다. list.json corp_code=%s %s".formatted(combo.size(), totalCount,
                                corpCode, detailType));
            }
            out.addAll(combo);
        }
        return out;
    }

    /**
     * 판 하나의 XBRL 인스턴스(ZIP 안 {@code .xbrl}) 바이트. 원장이 {@code 013} · {@code 014}(그 접수번호에 원본이 없음)를 답하면 빈 값이다.
     * {@code reprt_code}는 원장이 판을 고르는 데 안 쓴다(같은 ZIP이 온다 · {@code dart-xbrl.md}) — 그래도 필수 인자라 사업보고서 코드를 준다.
     */
    public Optional<byte[]> instance(String rceptNo) throws DataSourceException {
        requireKey();
        String context = "fnlttXbrl.xml rcept_no=" + rceptNo;
        DartResponse.acquire(rateLimiter, dailyQuota, XBRL_PATH, context);
        ResponseEntity<byte[]> response = get(context, uri -> uri.path(XBRL_PATH)
                .queryParam("crtfc_key", apiKey)
                .queryParam("rcept_no", rceptNo)
                .queryParam("reprt_code", "11011")
                .build());
        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new DataSourceException(status == 429 ? DataSourceException.Kind.RATE_LIMITED : DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "HTTP %d. %s".formatted(status, context));
        }
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME,
                    "HTTP %d인데 본문이 없다. %s".formatted(status, context));
        }
        if (body.length < 2 || body[0] != 'P' || body[1] != 'K') {
            DataSourceException e = DartResponse.errorFromXml(body, context);
            if (DartResponse.isNoData(e)) {
                return Optional.empty();
            }
            throw e;
        }
        return Optional.of(instanceEntry(body, context));
    }

    /** 사업연도 하나의 주식총수 명부(사업보고서 11011) 줄들. {@code 013}이면 빈 목록이다(그 해 보고서가 없다). */
    public List<JsonNode> shareTotals(String corpCode, int bsnsYear) throws DataSourceException {
        requireKey();
        String context = "stockTotqySttus corp_code=%s bsns_year=%d".formatted(corpCode, bsnsYear);
        DartResponse.acquire(rateLimiter, dailyQuota, SHARE_TOTALS_PATH, context);
        ResponseEntity<byte[]> response = get(context, uri -> uri.path(SHARE_TOTALS_PATH)
                .queryParam("crtfc_key", apiKey)
                .queryParam("corp_code", corpCode)
                .queryParam("bsns_year", bsnsYear)
                .queryParam("reprt_code", "11011")
                .build());
        try {
            List<JsonNode> rows = new ArrayList<>();
            DartResponse.requireList(DartResponse.read(response, context), context).forEach(rows::add);
            return rows;
        } catch (DataSourceException e) {
            if (DartResponse.isNoData(e)) {
                return List.of();
            }
            throw e;
        }
    }

    private ResponseEntity<byte[]> get(String context,
                                       java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri)
            throws DataSourceException {
        try {
            // 본문을 바이트로 받는다 — 상태를 먼저 보고 봉투를 판다(DartResponse.read · 문자열로 받으면 한글이 깨진다).
            return client.get().uri(uri).retrieve().toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }
    }

    /** ZIP에서 인스턴스 하나({@code .xbrl})를 꺼낸다. 없거나 둘 이상이면 본문 이상이다. */
    static byte[] instanceEntry(byte[] zip, String context) throws DataSourceException {
        byte[] found = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".xbrl")) {
                    continue;
                }
                if (found != null) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                            "ZIP에 인스턴스가 둘 이상이다. " + context);
                }
                found = in.readAllBytes();
            }
        } catch (IOException e) {
            // 잘린 ZIP은 받기 실패다(끊긴 전송) — 소스가 답한 것이 아니다.
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME, "ZIP을 못 풀었다. " + context, e);
        }
        if (found == null) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                    "ZIP에 인스턴스(.xbrl)가 없다. " + context);
        }
        return found;
    }

    private void requireKey() throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME, "DART_API_KEY가 설정되지 않았다.");
        }
    }
}
