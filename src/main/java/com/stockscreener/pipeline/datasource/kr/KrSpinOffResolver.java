package com.stockscreener.pipeline.datasource.kr;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 국내 인적분할 재상장일의 <b>신설회사</b>를 소스에게 묻는다(ADR-0205 규칙 4).
 *
 * <p>규칙: 존속회사의 DART 「회사분할 결정」({@code cmpDvDecsn} · 재상장일 365일 앞 ~ 그날) 중 신주 상장 예정일
 * ({@code abcr_nstklstprd})이 그날이고 신설회사가 재상장하는({@code dvfcmp_rlst_atn = 예}) 행이 <b>하나</b>면 그 분할기일({@code dvdt})을
 * 잡고, 그날 새로 상장된 종목(그날 시장 목록에 있고 전 거래일 목록에 없는 것) 중 DART 기업개황 설립일({@code est_dt})이 분할기일 ~ 7일 뒤인
 * 회사가 <b>하나</b>면 그것이 신설회사다. 신설회사는 분할기일에 설립 등기를 하므로 설립일이 분할기일(대개 1일) 뒤 첫 영업일이다.
 *
 * <p>이름으로 고르지 않는다 — 결정의 신설회사명(「주식회사 엘엑스홀딩스」 · 「에스케이스퀘어 주식회사 (SK Square Co., Ltd.)」)과 고유번호
 * 목록의 법인명(「LX홀딩스」 · 「SK스퀘어」)이 13 중 10에서 달랐다(2026-11-01 실측). 같은 날 신규 상장은 이전상장 · 공모 · 스팩이 섞인다
 * (효성 2024-07-29 산일전기 공모 · 솔브레인 2020-08-06 이루다) — 설립일이 그것들을 가른다. 같은 날 다른 회사의 인적분할 신설회사가 함께
 * 상장하면(삼성바이오로직스 2025-11-24 · 삼양바이오팜) 후보가 둘이라 짓지 않는다 — 호출자가 판정 대기로 남긴다.
 */
@Component
public class KrSpinOffResolver {

    private static final Logger log = LoggerFactory.getLogger(KrSpinOffResolver.class);

    static final int DECISION_LOOKBACK_DAYS = 365;
    static final int ESTABLISHED_WITHIN_DAYS = 7;

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern KOREAN_DATE = Pattern.compile("(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일");
    private static final int DAY_LIST_PAGE = 2_000;
    private static final int DAY_LIST_MAX_PAGES = 5;

    private final RestClient dart;
    private final RestClient prices;
    private final RateLimiter dartLimiter;
    private final RateLimiter pricesLimiter;
    private final DartDailyQuota dailyQuota;
    private final DartCorpCodeCache corpCodes;
    private final String dartKey;
    private final String serviceKey;

    @Autowired
    public KrSpinOffResolver(ExternalApiClientFactory factory, DataSourceProperties properties,
                             @Qualifier("dartRateLimiter") RateLimiter dartLimiter,
                             @Qualifier("dataGoKrRateLimiter") RateLimiter pricesLimiter,
                             DartDailyQuota dailyQuota, DartCorpCodeCache corpCodes) {
        this.dart = factory.create(properties.getDart());
        this.prices = factory.create(properties.getDataGoKr());
        this.dartLimiter = dartLimiter;
        this.pricesLimiter = pricesLimiter;
        this.dailyQuota = dailyQuota;
        this.corpCodes = corpCodes;
        this.dartKey = properties.getDart().getApiKey();
        this.serviceKey = ExternalApiClientFactory.normalizeServiceKey(properties.getDataGoKr().getApiKey());
    }

    /** 신설회사 — 종목코드와 재상장일 시가총액(소스 {@code mrktTotAmt}). */
    public record Child(String ticker, BigDecimal marketCap) {
    }

    /** 신설회사를 하나로 못 고르면 비어 있다(사유는 로그 · 호출자가 판정 대기로 남긴다). */
    public Optional<Child> resolve(String parentTicker, LocalDate relisted, LocalDate previousTradingDay) throws DataSourceException {
        String context = "인적분할 " + parentTicker + " " + relisted;
        Optional<String> corpCode = corpCodes.findCorpCode(parentTicker);
        if (corpCode.isEmpty()) {
            log.warn("{} — 존속회사의 DART 고유번호가 없다.", context);
            return Optional.empty();
        }
        List<LocalDate> divisionDays = new ArrayList<>();
        for (JsonNode row : decisions(corpCode.get(), relisted, context)) {
            if (relisted.equals(koreanDate(row.path("abcr_nstklstprd").asString("")))
                    && "예".equals(row.path("dvfcmp_rlst_atn").asString("").strip())) {
                LocalDate division = koreanDate(row.path("dvdt").asString(""));
                if (division != null) {
                    divisionDays.add(division);
                }
            }
        }
        if (divisionDays.size() != 1) {
            log.warn("{} — 그날 상장하는 재상장 분할 결정이 {}건이다(하나여야 한다).", context, divisionDays.size());
            return Optional.empty();
        }
        LocalDate division = divisionDays.get(0);
        Map<String, JsonNode> today = dayList(relisted, context);
        Map<String, JsonNode> before = dayList(previousTradingDay, context);
        List<Child> candidates = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : today.entrySet()) {
            if (before.containsKey(entry.getKey())) {
                continue;
            }
            Optional<String> childCorp = corpCodes.findCorpCode(entry.getKey());
            if (childCorp.isEmpty()) {
                continue;   // 우선주 · 상장지수상품은 고유번호 목록에 종목코드로 없다
            }
            LocalDate established = established(childCorp.get(), context);
            if (established != null && !established.isBefore(division)
                    && !established.isAfter(division.plusDays(ESTABLISHED_WITHIN_DAYS))) {
                String cap = entry.getValue().path("mrktTotAmt").asString("");
                if (!cap.isBlank()) {
                    candidates.add(new Child(entry.getKey(), new BigDecimal(cap.replace(",", ""))));
                }
            }
        }
        if (candidates.size() != 1) {
            log.warn("{} — 그날 신규 상장 중 설립일이 분할기일 {} ~ {}일 뒤인 회사가 {}개다(하나여야 한다): {}",
                    context, division, ESTABLISHED_WITHIN_DAYS, candidates.size(), candidates);
            return Optional.empty();
        }
        return Optional.of(candidates.get(0));
    }

    private JsonNode decisions(String corpCode, LocalDate relisted, String context) throws DataSourceException {
        String path = "/cmpDvDecsn.json";
        DartResponse.acquire(dartLimiter, dailyQuota, path, context);
        ResponseEntity<byte[]> response;
        try {
            response = dart.get().uri(uri -> uri.path(path)
                            .queryParam("crtfc_key", dartKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bgn_de", relisted.minusDays(DECISION_LOOKBACK_DAYS).format(COMPACT))
                            .queryParam("end_de", relisted.format(COMPACT))
                            .build())
                    .retrieve().toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }
        try {
            return DartResponse.requireList(DartResponse.read(response, context), context);
        } catch (DataSourceException e) {
            if (DartResponse.isNoData(e)) {
                return tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            }
            throw e;
        }
    }

    private LocalDate established(String corpCode, String context) throws DataSourceException {
        String path = "/company.json";
        DartResponse.acquire(dartLimiter, dailyQuota, path, context);
        ResponseEntity<byte[]> response;
        try {
            response = dart.get().uri(uri -> uri.path(path).queryParam("crtfc_key", dartKey).queryParam("corp_code", corpCode).build())
                    .retrieve().toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }
        String raw = DartResponse.requireSuccess(DartResponse.read(response, context), context).path("est_dt").asString("").strip();
        return raw.matches("\\d{8}") ? LocalDate.parse(raw, COMPACT) : null;
    }

    /** 그날 시장 전 종목 — 종목코드 → 행. {@code totalCount}까지 다 받지 못하면 던진다(잘린 목록이면 「새로 상장」을 지어낸다). */
    private Map<String, JsonNode> dayList(LocalDate day, String context) throws DataSourceException {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        int total = Integer.MAX_VALUE;
        int received = 0;
        for (int page = 1; received < total; page++) {
            if (page > DAY_LIST_MAX_PAGES) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, DataGoKrResponse.SOURCE_NAME,
                        "그날 목록이 " + DAY_LIST_MAX_PAGES + "쪽을 넘는다. " + context + " " + day);
            }
            int p = page;
            if (!pricesLimiter.hasDailyQuota()) {
                throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED, DataGoKrResponse.SOURCE_NAME,
                        "일일 호출 한도를 소진했다. " + context);
            }
            try {
                pricesLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DataGoKrResponse.SOURCE_NAME, "대기 중 인터럽트. " + context, e);
            }
            ResponseEntity<JsonNode> response;
            try {
                response = prices.get().uri(uri -> uri.path("/getStockPriceInfo")
                                .queryParam("serviceKey", serviceKey).queryParam("resultType", "json")
                                .queryParam("numOfRows", DAY_LIST_PAGE).queryParam("pageNo", p)
                                .queryParam("basDt", day.format(COMPACT)).build())
                        .retrieve().toEntity(JsonNode.class);
            } catch (RuntimeException e) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, DataGoKrResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
            }
            JsonNode body = DataGoKrResponse.requireBody(response.getStatusCode(), response.getBody(), context);
            total = body.path("totalCount").asInt(0);
            JsonNode items = body.path("items").path("item");
            int inPage = 0;
            for (JsonNode item : items) {
                inPage++;
                out.put(item.path("srtnCd").asString(""), item);
            }
            received += inPage;
            if (total == 0) {
                throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, DataGoKrResponse.SOURCE_NAME,
                        "그날 시장 목록이 비었다(휴장일이면 날짜가 틀렸다). " + context + " " + day);
            }
            if (inPage == 0) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, DataGoKrResponse.SOURCE_NAME,
                        "totalCount=" + total + "인데 " + received + "행에서 빈 쪽이 왔다. " + context);
            }
        }
        return out;
    }

    /** 「2024년 07월 29일」 꼴 · 「-」 같은 빈 값은 null. */
    static LocalDate koreanDate(String raw) {
        Matcher m = KOREAN_DATE.matcher(raw == null ? "" : raw);
        return m.find() ? LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))) : null;
    }
}
