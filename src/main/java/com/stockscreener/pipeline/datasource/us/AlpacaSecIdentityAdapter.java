package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.IdentitySource;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 미국 신원 판정 재료(ADR-0180) — Alpaca 목록 · 실체 봉 · 기업 행위와 SEC 등록인 파일 · companyfacts · 공시 목록.
 *
 * <p>받기는 기존 어댑터의 것을 그대로 쓴다 — 목록은 {@link AlpacaUniverseAdapter#fetchActiveAssets} · 신원 사건은
 * {@link AlpacaCorporateActionAdapter#fetchIdentityEvents} · companyfacts는 {@link SecEdgarFinancialAdapter#companyFacts} · 공시 목록은
 * {@link SecFilingClient#filings} · 등록인 파일은 {@link SecTickerCikCache#freshSnapshot}. 여기서 새로 부르는 것은 <b>여러 기호의
 * 실체 봉</b> 하나다 — 시세 어댑터는 기호 하나씩, 순위 어댑터는 종가·거래대금만 낸다.
 *
 * <h2>실체 봉의 {@code asof}</h2>
 * <ul>
 *   <li>날짜 — 그날 그 기호가 가리키던 실체(개명 뒤에도 같은 실체 · {@code HLX@08-28} = HOS 봉)</li>
 *   <li>없음(소스 기본) — 오늘 그 기호의 실체. 개명된 실체는 옛 기호 시절 봉까지 온다</li>
 *   <li>{@code -} — 그 문자열로 거래된 봉만. 실체를 안 따라가므로 개명일을 읽는다(HOS 첫 봉 09-02 = 원장 개명 처리일)</li>
 * </ul>
 * 응답 키는 요청 기호다. <b>다기호 요청에서 한 실체로 접히는 두 기호는 하나만 온다</b>(실측 {@code CYCN,KRSA@08-28} → CYCN만) — 같은
 * 요청에 같은 실체의 두 기호가 들 수 없게 호출부가 묶는다(닻 날짜별 · 오늘 목록 기호끼리).
 */
@Component
@ConditionalOnApiKey("alpaca")
public class AlpacaSecIdentityAdapter implements IdentitySource {

    static final String SOURCE_NAME = "alpaca+sec:identity";

    private static final int SYMBOLS_PER_CALL = 100;
    private static final int PAGE_LIMIT = 10_000;
    private static final int MAX_PAGES = 50;
    private static final Duration END_MARGIN = Duration.ofMinutes(16);
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final AlpacaUniverseAdapter listing;
    private final AlpacaCorporateActionAdapter corporateActions;
    private final SecTickerCikCache cikCache;
    private final ObjectProvider<SecEdgarFinancialAdapter> secFacts;
    private final ObjectProvider<SecFilingClient> secFilings;

    @Autowired
    public AlpacaSecIdentityAdapter(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                                    @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter,
                                    AlpacaUniverseAdapter listing, AlpacaCorporateActionAdapter corporateActions,
                                    SecTickerCikCache cikCache, ObjectProvider<SecEdgarFinancialAdapter> secFacts,
                                    ObjectProvider<SecFilingClient> secFilings) {
        this(clientFactory, properties, rateLimiter, properties.getAlpacaMarketDataBaseUrl(), listing, corporateActions,
                cikCache, secFacts, secFilings);
    }

    // MockWebServer 시험 전용 — 봉의 base URL을 바꿔 끼운다.
    AlpacaSecIdentityAdapter(ExternalApiClientFactory clientFactory, DataSourceProperties properties,
                             RateLimiter rateLimiter, String baseUrl, AlpacaUniverseAdapter listing,
                             AlpacaCorporateActionAdapter corporateActions, SecTickerCikCache cikCache,
                             ObjectProvider<SecEdgarFinancialAdapter> secFacts, ObjectProvider<SecFilingClient> secFilings) {
        DataSourceProperties.Source source = properties.getAlpaca();
        this.client = clientFactory.create(source, baseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.listing = listing;
        this.corporateActions = corporateActions;
        this.cikCache = cikCache;
        this.secFacts = secFacts;
        this.secFilings = secFilings;
    }

    @Override
    public List<Listed> listedSecurities() throws DataSourceException {
        List<Listed> out = new ArrayList<>();
        for (AlpacaAsset asset : listing.fetchActiveAssets()) {
            out.add(new Listed(asset.symbol(), asset.name(), asset.exchange()));
        }
        return out;
    }

    @Override
    public RegistrantFile registrantFile() throws DataSourceException {
        SecTickerCikCache.Snapshot snapshot = cikCache.freshSnapshot();
        return snapshot::findCik;
    }

    @Override
    public Map<String, List<EntityBar>> bars(Collection<String> symbols, BarAsOf asOf, LocalDate from, LocalDate to)
            throws DataSourceException {
        List<String> all = new ArrayList<>(new java.util.TreeSet<>(symbols));
        Map<String, List<EntityBar>> out = new LinkedHashMap<>();
        Instant end = clampEnd(to);
        for (int offset = 0; offset < all.size(); offset += SYMBOLS_PER_CALL) {
            List<String> chunk = all.subList(offset, Math.min(all.size(), offset + SYMBOLS_PER_CALL));
            String joined = String.join(",", chunk);
            String pageToken = null;
            int page = 0;
            do {
                String context = "identity bars asof=%s %d~%d/%d page=%d".formatted(
                        asOf.literal() ? "-" : asOf.date() == null ? "기본" : asOf.date(),
                        offset + 1, offset + chunk.size(), all.size(), page + 1);
                ResponseEntity<String> response = call(joined, asOf, from, end, pageToken, context);
                AlpacaResponse.Envelope envelope = AlpacaResponse.requireBars(
                        response.getStatusCode(), response.getBody(), SOURCE_NAME, context);
                for (Map.Entry<String, JsonNode> entry : envelope.value().properties()) {
                    List<EntityBar> bars = out.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                    for (JsonNode bar : entry.getValue()) {
                        bars.add(toBar(bar, entry.getKey(), context));
                    }
                }
                pageToken = envelope.nextPageToken();
                page++;
                if (page >= MAX_PAGES && pageToken != null) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                            "페이지가 %d를 넘는다 — 부분 결과를 버린다. %s".formatted(MAX_PAGES, context));
                }
            } while (pageToken != null);
        }
        out.values().forEach(list -> list.sort(java.util.Comparator.comparing(EntityBar::date)));
        return out;
    }

    @Override
    public Set<String> registrantAccessions(long cik) throws DataSourceException {
        String context = "identity companyfacts " + SecTickerCikCache.padded(cik);
        JsonNode facts;
        try {
            facts = requireSec(secFacts.getIfAvailable(), "companyfacts").companyFacts(cik, context);
        } catch (DataSourceException e) {
            if (e.kind() == DataSourceException.Kind.NOT_FOUND) {
                return Set.of(); // XBRL을 낸 적이 없는 등록인 — 공유할 접수번호가 원리적으로 없다
            }
            throw e;
        }
        return CompanyFactsAccessions.of(facts);
    }

    @Override
    public List<RegistrantFiling> registrantFilings(long cik, LocalDate since) throws DataSourceException {
        List<RegistrantFiling> out = new ArrayList<>();
        for (SecFilingClient.Filing filing : requireSec(secFilings.getIfAvailable(), "submissions").filings(cik, since)) {
            out.add(new RegistrantFiling(filing.form(), filing.filed()));
        }
        return out;
    }

    @Override
    public List<IdentityEvent> identityEvents(Collection<String> symbols, LocalDate from, LocalDate to)
            throws DataSourceException {
        return corporateActions.fetchIdentityEvents(symbols, from, to);
    }

    @Override
    public List<EndEvent> endEvents(LocalDate from, LocalDate to) throws DataSourceException {
        return corporateActions.fetchEndEvents(from, to);
    }

    @Override
    public List<SymbolChange> symbolChanges(Collection<String> symbols, LocalDate from, LocalDate to) throws DataSourceException {
        return corporateActions.fetchSymbolChanges(symbols, from, to);
    }

    @Override
    public java.util.Optional<Listed> asset(String symbolOrCusip) throws DataSourceException {
        return listing.fetchAsset(symbolOrCusip).map(a -> new Listed(a.symbol(), a.name(), a.exchange()));
    }

    private static <T> T requireSec(T bean, String what) throws DataSourceException {
        if (bean == null) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "SEC 키(SEC_EDGAR_USER_AGENT)가 없어 " + what + "를 못 받는다 — 신원 판정의 행 끊기 전제를 못 잰다.");
        }
        return bean;
    }

    private static EntityBar toBar(JsonNode bar, String symbol, String context) throws DataSourceException {
        try {
            LocalDate date = Instant.parse(bar.path("t").asString("")).atZone(MARKET_ZONE).toLocalDate();
            if (!bar.path("c").isNumber() || !bar.path("v").isNumber() || !bar.path("n").isNumber()) {
                throw new IllegalArgumentException("c·v·n 중 수가 아닌 것이 있다");
            }
            return new EntityBar(date, bar.path("c").decimalValue(), bar.path("v").asLong(), bar.path("n").asLong());
        } catch (RuntimeException e) {
            // 파싱 실패는 벤더 스펙 변경의 신호다 — 0으로 읽으면 다른 실체가 같은 실체로 보일 수 있다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "봉을 해석할 수 없다: " + symbol + " " + bar + ". " + context, e);
        }
    }

    /** {@code AlpacaPriceAdapter.clampEnd}와 같은 계약이다 — 15분 규칙을 구조적으로 못 넘는다. */
    private static Instant clampEnd(LocalDate to) {
        Instant requested = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant safe = Instant.now().minus(END_MARGIN);
        return requested.isBefore(safe) ? requested : safe;
    }

    private ResponseEntity<String> call(String symbols, BarAsOf asOf, LocalDate from, Instant end, String pageToken,
                                        String context) throws DataSourceException {
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED, SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출 대기 중 인터럽트됐다. " + context, e);
        }
        try {
            return client.get()
                    .uri(uri -> {
                        uri.path("/v2/stocks/bars")
                                .queryParam("symbols", symbols)
                                .queryParam("timeframe", "1Day")
                                .queryParam("start", from.toString())
                                .queryParam("end", end.toString())
                                .queryParam("feed", "sip")
                                .queryParam("adjustment", "raw")
                                .queryParam("limit", PAGE_LIMIT)
                                .queryParam("sort", "asc");
                        if (asOf.literal()) {
                            uri.queryParam("asof", "-");
                        } else if (asOf.date() != null) {
                            uri.queryParam("asof", asOf.date().toString());
                        }
                        if (pageToken != null) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    // MUST: 문자열로 받는다 — 오류 본문이 JSON이 아니다(AlpacaResponse).
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }
    }
}
