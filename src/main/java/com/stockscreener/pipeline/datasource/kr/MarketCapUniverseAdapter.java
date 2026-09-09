package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import tools.jackson.databind.JsonNode;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.UniverseDataSource;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 시가총액 상위 N종목을 유니버스로 삼는다(ADR-0012).
 *
 * <p>지수 구성종목을 주는 공식 무료 API가 없고 CSV를 영구히 구할 수 없다는 판단에서 나온
 * 구현이다. 공공데이터포털 시세 API에 {@code basDt}만 주면 그날 상장 전 종목이 오고, 응답에
 * {@code mrktTotAmt}(시가총액)·{@code itmsNm}(종목명)·{@code mrktCtg}(시장)가 들어 있다.
 * 실측: 전종목 2,872건이 1콜 785KB로 온다.
 *
 * <p>IMPORTANT — 이것은 <b>코스피200이 아니다.</b> 실제 지수는 유동비율과 업종 안배를
 * 반영해 시총 순위와 다르다. 그래서 유니버스 코드도 {@code KOSPI_TOP200}처럼 따로 쓴다 —
 * 지수명을 차용하면 화면에 "코스피200"이라 적히는데 내용은 다른 상태가 되고, 나중에 그
 * 차이를 설명할 수 없다.
 *
 * <p>기준일을 넘겨받으므로 <b>과거 시점도 재현된다</b>(2020-01-02까지 실측 확인).
 */
@ConditionalOnApiKey("data-go-kr")
@Component
public class MarketCapUniverseAdapter implements UniverseDataSource {

    private static final Logger log = LoggerFactory.getLogger(MarketCapUniverseAdapter.class);

    private static final String SOURCE_NAME = "data-go-kr:market-cap";
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 전종목이 시장별 2,000건 미만이라 한 페이지로 들어온다. 그래도 페이징은 구현한다. */
    private static final int PAGE_SIZE = 2000;
    private static final int MAX_PAGES = 20;

    /**
     * 휴장일 대비 소급 한도.
     *
     * <p>기준일이 휴장일이면 전종목 응답이 비어 있다. 그것을 "구성종목 없음"으로 반영하면
     * 유니버스가 통째로 비므로, 직전 거래일을 찾아 거슬러 올라간다. 연휴가 가장 긴 경우
     * (설·추석 + 주말)를 감안해 10일을 잡는다.
     */
    private static final int MAX_LOOKBACK_DAYS = 10;

    /**
     * 경계 구간을 로그로 남기는 폭. <b>편입 판정에 안 쓰인다.</b>
     *
     * <p><b>MUST NOT: 이것을 히스테리시스로 읽지 않는다.</b> {@link #select}는 <b>순수 상위 N</b>을
     * 반환하고 이 값은 <b>{@code log.debug} 한 줄</b>에만 쓰인다 — 「이미 편입된 종목은 N×1.1
     * 이내면 유지한다」는 <b>코드가 한 적이 없는 일</b>이고, 도입 커밋 이후 이 자리는 한 번도
     * 안 바뀌었다.
     *
     * <p>그 오독이 <b>문서 일곱 곳에 퍼져</b> 「과거 유니버스는 경로 의존이라 as-of 1콜로 복원
     * 안 된다」의 근거로 인용됐고, 그 위에서 복원이 **1년 넘게 막혀 있었다**(ADR-0135가 그것을
     * 걷었다). 실제 경로 의존은 {@code UniverseReconciler}(실행일 스탬프 · 제외 비율 거부 ·
     * 같은 날 DELETE 갈래)와 미국의 {@code fetchActiveAssets()}에 있다.
     *
     * <p>밴드를 <b>실제로</b> 걸고 싶으면 여기가 아니라 {@code UniverseReconciler}다 — 어댑터는
     * 현재 편입 목록을 모르므로 유지 판정을 할 수 없다({@link #select}의 javadoc이 그 이유를 적는다).
     */
    private static final double RETENTION_BAND = 1.1;

    /**
     * 보통주 판별 — 국내 종목코드는 끝자리가 0이면 보통주다.
     *
     * <p>{@code 005930} 삼성전자 / {@code 005935} 삼성전자우. 실측상 시총 상위 200에
     * 우선주가 5종목 섞이며, 거르지 않으면 같은 회사가 두 번 들어가 스크리닝 결과가 중복된다.
     */
    private static final Pattern COMMON_STOCK = Pattern.compile("\\d{5}0");

    /**
     * 스팩·리츠·ETF 제외 패턴.
     *
     * <p>사업회사가 아니라 재무 지표가 의미를 갖지 않는다. 실측상 상위 200에 2종목이 섞인다.
     */
    private static final Pattern NON_OPERATING = Pattern.compile(
            "스팩|제\\d+호|리츠|KODEX|TIGER|KBSTAR|ARIRANG|HANARO|SOL |ACE |PLUS |RISE ");

    /** 유니버스 코드 → (시장 코드, 상위 N). */
    private static final Map<String, Target> TARGETS = Map.of(
            "KOSPI_TOP200", new Target("KOSPI", 200),
            "KOSDAQ_TOP150", new Target("KOSDAQ", 150));

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final String serviceKey;
    private final SecurityNameCache nameCache;
    private final Map<String, Target> targets;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 기본 생성자를 찾다가 {@code NoSuchMethodException}으로 <b>기동에 실패한다.</b>
     * 단위 테스트는 생성자를 직접 부르므로 이 결함을 잡지 못한다 — 실제로 기동해서야 드러났다.
     */
    @Autowired
    public MarketCapUniverseAdapter(ExternalApiClientFactory clientFactory,
                                    DataSourceProperties properties,
                                    @Qualifier("dataGoKrRateLimiter") RateLimiter rateLimiter,
                                    SecurityNameCache nameCache) {
        this(clientFactory, properties, rateLimiter, nameCache, TARGETS);
    }

    /**
     * 상위 N을 바꿔 끼우는 생성자. 테스트 전용이다.
     *
     * <p>운영 값(200·150)으로는 픽스처에 200종목을 넣어야 해서 선정 규칙 하나를 확인하는 데
     * 무관한 잡음이 잔뜩 필요해진다. 규칙 자체는 N과 무관하므로 작은 N으로 검증한다.
     */
    MarketCapUniverseAdapter(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             RateLimiter rateLimiter,
                             SecurityNameCache nameCache,
                             Map<String, Target> targets) {
        DataSourceProperties.Source source = properties.getDataGoKr();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.serviceKey = ExternalApiClientFactory.normalizeServiceKey(source.getApiKey());
        this.nameCache = nameCache;
        this.targets = Map.copyOf(targets);
    }

    @Override
    public Set<String> supportedUniverseCodes() {
        return targets.keySet();
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public UniverseSnapshot fetchConstituents(String universeCode, LocalDate asOf)
            throws DataSourceException {
        Target target = targets.get(universeCode);
        if (target == null) {
            throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, SOURCE_NAME,
                    "담당하지 않는 유니버스다: " + universeCode);
        }
        requireKey();

        List<Candidate> candidates = fetchCandidates(target.marketCode(), asOf);
        if (candidates.size() < target.topN()) {
            // MUST(ADR-0008): 부분 목록을 스냅샷으로 만들지 않는다. 리밸런싱이 "목록에 없으면
            // 제외"로 동작하므로, 모자란 목록은 대량 오탈락이 된다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "상위 %d종목을 채우지 못했다: %s에서 %d종목만 얻었다 — 부분 목록을 반영하지 않는다."
                            .formatted(target.topN(), universeCode, candidates.size()));
        }

        candidates.sort(Comparator.comparing(Candidate::marketCap).reversed());

        // 이름을 캐시에 넣어 StockRegistrar가 종목마다 마스터 API를 부르지 않게 한다.
        for (Candidate candidate : candidates) {
            nameCache.put(new SecurityDescriptor(
                    candidate.marketCode(), candidate.ticker(), candidate.name(),
                    null, null, null, null, null));
        }

        List<String> tickers = select(candidates, target.topN());
        log.info("시총 상위 유니버스를 만들었다. {} 기준일={} 후보={}종목 → 편입={}종목",
                universeCode, asOf, candidates.size(), tickers.size());
        return new UniverseSnapshot(universeCode, asOf, tickers, SOURCE_NAME);
    }

    /**
     * 상위 N을 고르되 기존 편입 종목에는 여유를 준다.
     *
     * <p>현재 편입 목록을 이 어댑터가 알지 못하므로 히스테리시스는 <b>순위 밴드</b>로만
     * 표현한다 — 상위 N까지는 무조건 넣고, N을 살짝 넘은 구간은 넣지 않는다. 실제 유지 판정은
     * {@code UniverseReconciler}가 집합 차집합으로 하며, 여기서 밴드를 넓혀 목록을 부풀리면
     * "N종목 유니버스"라는 정의 자체가 흐려진다.
     *
     * <p>대신 <b>경계 근처를 로그로 남긴다.</b> 교체가 잦아지면 밴드 조정의 근거가 된다.
     */
    private List<String> select(List<Candidate> sorted, int topN) {
        List<String> tickers = new ArrayList<>(topN);
        for (int i = 0; i < topN; i++) {
            tickers.add(sorted.get(i).ticker());
        }

        int bandEnd = Math.min(sorted.size(), (int) (topN * RETENTION_BAND));
        if (bandEnd > topN) {
            log.debug("경계 구간({}~{}위): {}", topN + 1, bandEnd,
                    sorted.subList(topN, bandEnd).stream().map(Candidate::name).toList());
        }
        return tickers;
    }

    /**
     * 기준일의 전종목을 받아 후보로 거른다. 응답이 비면 직전 날짜로 거슬러 올라간다.
     *
     * <p>MUST NOT: 빈 응답을 <b>휴장으로 단정하는 문구</b>를 남기지 않는다. 이 소스는 휴장·
     * 미공개·없는 종목·파라미터 오타에 <b>전부 같은 응답</b>을 주므로 어댑터는 그것을 구분할
     * 수 없다({@code MarketCalendar}의 javadoc이 같은 사실을 적는다). 소급하는 <b>동작</b>은
     * 어느 쪽이든 옳지만, 단정하는 <b>로그</b>는 읽는 쪽을 틀리게 만든다 — 실제로 이 줄이
     * "2026-08-07은 휴장이다"의 근거로 인용되어 종가 결손이 사흘간 방치됐다
     * ({@code docs/api-spec/data-go-kr-stock-price.md} §공개 지연).
     */
    private List<Candidate> fetchCandidates(String marketCode, LocalDate asOf)
            throws DataSourceException {
        for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
            LocalDate basDt = asOf.minusDays(back);
            List<Candidate> candidates = fetchOneDay(marketCode, basDt);
            if (!candidates.isEmpty()) {
                if (back > 0) {
                    log.info("{}에 시세가 없어 {}의 시세로 유니버스를 만든다. "
                            + "휴장인지 아직 공개되지 않은 것인지는 응답으로 구분할 수 없다.",
                            asOf, basDt);
                }
                return candidates;
            }
        }
        throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, SOURCE_NAME,
                "%d일을 거슬러 올라가도 %s의 시세가 없다. 기준일=%s"
                        .formatted(MAX_LOOKBACK_DAYS, marketCode, asOf));
    }

    private List<Candidate> fetchOneDay(String marketCode, LocalDate basDt)
            throws DataSourceException {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int received = 0;
        int totalCount = Integer.MAX_VALUE;
        String context = "universe " + marketCode + " basDt=" + basDt;

        for (int pageNo = 1; received < totalCount; pageNo++) {
            if (pageNo > MAX_PAGES) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "페이지 수가 상한을 넘었다(" + MAX_PAGES + "). " + context);
            }
            final int page = pageNo;
            JsonNode body = callBody(uri -> uri
                    .path("/getStockPriceInfo")
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("resultType", "json")
                    .queryParam("numOfRows", PAGE_SIZE)
                    .queryParam("pageNo", page)
                    .queryParam("basDt", basDt.format(BAS_DT))
                    .queryParam("mrktCls", marketCode)
                    .build(), context + " page=" + page);

            totalCount = body.path("totalCount").asInt(0);
            if (totalCount == 0) {
                // 휴장일이다. 호출부가 직전 거래일로 거슬러 올라간다.
                return List.of();
            }

            JsonNode items = body.path("items").path("item");
            int itemsInPage = 0;
            for (JsonNode item : items) {
                itemsInPage++;
                toCandidate(item, marketCode, seen).ifPresent(candidates::add);
            }
            received += itemsInPage;

            if (itemsInPage == 0) {
                throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                        "totalCount=" + totalCount + "인데 " + received
                                + "행에서 빈 페이지가 왔다 — 부분 목록을 만들지 않는다. " + context);
            }
        }
        return candidates;
    }

    /**
     * 응답 한 행을 후보로 옮긴다. 조건에 맞지 않으면 비어 있다.
     *
     * <p>{@code seen}으로 중복을 막는다. {@code UniverseSnapshot}이 중복 티커를 거부하므로,
     * 소스가 같은 종목을 두 번 주면 스냅샷 생성이 예외로 끝난다.
     */
    private java.util.Optional<Candidate> toCandidate(JsonNode item, String marketCode,
                                                      Set<String> seen) {
        String ticker = item.path("srtnCd").asText("");
        String name = item.path("itmsNm").asText("");
        String market = item.path("mrktCtg").asText("");

        if (!marketCode.equals(market)) {
            return java.util.Optional.empty();
        }
        if (!COMMON_STOCK.matcher(ticker).matches()) {
            return java.util.Optional.empty();
        }
        if (name.isBlank() || NON_OPERATING.matcher(name).find()) {
            return java.util.Optional.empty();
        }

        String rawCap = item.path("mrktTotAmt").asText("");
        if (rawCap.isBlank() || "-".equals(rawCap)) {
            return java.util.Optional.empty();
        }
        BigDecimal marketCap;
        try {
            marketCap = new BigDecimal(rawCap.replace(",", ""));
        } catch (NumberFormatException e) {
            // 시가총액을 해석할 수 없으면 순위를 매길 수 없다. 조용히 0으로 두면 목록 맨 끝에
            // 놓이는데, 그것은 "작은 회사"와 구별되지 않는다.
            return java.util.Optional.empty();
        }
        if (marketCap.signum() <= 0 || !seen.add(ticker)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Candidate(ticker, name, marketCode, marketCap));
    }

    private JsonNode callBody(java.util.function.Function<org.springframework.web.util.UriBuilder,
            java.net.URI> uriSpec, String context) throws DataSourceException {
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

        ResponseEntity<JsonNode> response;
        try {
            response = client.get().uri(uriSpec).retrieve().toEntity(JsonNode.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }
        return DataGoKrResponse.requireBody(response.getStatusCode(), response.getBody(), context);
    }

    private void requireKey() throws DataSourceException {
        if (serviceKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "DATA_GO_KR_SERVICE_KEY가 설정되지 않았다.");
        }
    }

    record Target(String marketCode, int topN) {
    }

    private record Candidate(String ticker, String name, String marketCode, BigDecimal marketCap) {
    }
}
