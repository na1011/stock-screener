package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityDescriptor;
import com.stockscreener.pipeline.datasource.SecurityNameCache;
import com.stockscreener.pipeline.datasource.StoredShareCounts;
import com.stockscreener.pipeline.datasource.UniverseDataSource;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 미국에서 <b>스크리닝에 노출할</b> 유니버스 — 시가총액 상위 N. 결정은 ADR-0099다.
 *
 * <p>{@link AlpacaUniverseAdapter}({@code US_LISTED})와 <b>목적이 다르다.</b> 저쪽은
 * 「적재는 넓게」의 쪽이라 NYSE·NASDAQ 상장 전량(실측 8,504)을 담고, 이쪽은 「노출은 좁게」의
 * 쪽이다(ADR-0086 §규칙). 국내가 시총 상위 350/2,872인 것과 대칭이다.
 *
 * <h2>시총을 두 소스에서 합성한다 — 이 어댑터의 핵심이자 위험</h2>
 * <p>미국 시세 소스는 상장주식수를 주지 않고(ADR-0013 · {@code price_daily.listed_shares}가
 * 미국 전 행 NULL) 재무 소스는 종가를 주지 않는다. 그래서
 * <b>{@link StoredShareCounts}(우리 DB의 재무) × Alpaca의 최근 종가</b>다. 국내는 전종목 시세
 * 응답 하나에 {@code mrktTotAmt}가 함께 와서 이 합성이 필요 없었다.
 *
 * <p><b>알고도 남기는 오차</b>: 주식수는 보고 종료일 기준이고 종가는 오늘이라, 그 사이에
 * 분할이 있으면 시총이 배수만큼 틀린다. 결측이 아니라 <b>그럴듯한 숫자</b>로 나온다.
 * {@code shiftToBar}(ADR-0096)는 지표 계층에 있고 이 조인은 타지 않는다. <b>몇 종목인지는
 * 재보지 않았다</b> — 그래서 §대조군이 「빠진 것」만이 아니라 상위권 이름도 함께 신고한다.
 *
 * <h2>배제축이 넷이고, 하나로는 안 닫힌다</h2>
 * <p>수는 전부 2026-08-31 실측이며 모수는 <b>통화 게이트를 통과한 후보 4,515</b>다.
 * <ol>
 *   <li><b>통화</b> — {@link StoredShareCounts.ShareCount#currencyMismatched()}. 전 종목 197.
 *       정제 없이 넣으면 상단이 통째로 오염된다(정제 전 1위가 {@code BSAC} 6,622B —
 *       재무가 CLP인데 종가가 USD다).</li>
 *   <li><b>SIC</b> — {@code 6798}(REIT)·{@code 6770}(SPAC) 166건. 국내가 ADR-0012 §규칙에서
 *       스팩·리츠를 뺀 것과 대칭이고, <b>이름이 아니라 코드로</b> 판정한다.</li>
 *   <li><b>이름</b> — 증권 종류를 가리키는 낱말. 아래 {@link #NON_COMMON_NAME}.</li>
 *   <li><b>CIK 중복</b> — 우선주·baby bond가 발행사의 주식수를 그대로 써서 같은 회사가 여러 번
 *       들어온다(상위 1,000에 98건). {@code AKO.A}/{@code AKO.B}처럼 두 클래스가 다 상장된
 *       쌍도 여기서 걸린다.</li>
 * </ol>
 *
 * <p><b>MUST NOT: 티커의 점을 축으로 쓰지 않는다.</b> 의결권 클래스 23건이 그 규칙에 사라지고
 * 거기에 {@code BRK.A}·{@code BRK.B}가 들어 있다({@code alpaca-universe.md}의 IMPORTANT).
 * 중복은 CIK로 가른다.
 *
 * <h2>이 유니버스가 이름값을 못 하는 자리</h2>
 * <p>후보는 <b>우리 DB에 주식수가 있는 종목</b>에서만 나온다. 그래서 재무를 파싱해 내지 못한
 * 종목은 시총이 아무리 커도 후보가 아니다 — 실측으로 {@code AMZN}·{@code JNJ}·{@code XOM}·
 * {@code CSCO}(재무 0행)와 {@code V}·{@code BRK.A}/{@code BRK.B}(주식수 전량 NULL)가 그 상태다.
 * <b>「시총 상위 N」이 아니라 「우리가 재무를 파싱해 낸 종목 중 시총 상위 N」이다.</b>
 * 여는 조건은 ADR-0099 §결과에 있다.
 */
@Component
@ConditionalOnApiKey("alpaca")
public class UsMarketCapUniverseAdapter implements UniverseDataSource {

    static final String SOURCE_NAME = "alpaca:market-cap";

    private static final Logger log = LoggerFactory.getLogger(UsMarketCapUniverseAdapter.class);

    /**
     * 유니버스 코드. <b>N이 코드에 들어 있는 것이 의도다.</b>
     *
     * <p>ADR-0012 §규칙이 금지한 것은 <b>지수명 차용</b>이지 N이 아니다 — {@code KOSPI200}이라는
     * 이름에 시총 상위 200을 담지 말라는 것이고, {@code KOSPI_TOP200}의 200도 우리가 고른 값이다.
     * N을 코드에서 빼면 N이 바뀌는 날 <b>같은 코드의 편입 원장이 두 정의를 담고</b> 어느
     * 정의였는지 사후에 구분할 수 없다(ADR-0086이 {@code SP500} 재사용을 기각한 그 형태다).
     * 넣으면 N 변경이 <b>새 코드를 강제</b>해 원장이 정직해진다.
     */
    public static final String UNIVERSE_CODE = "US_TOP1000";

    /** {@link #UNIVERSE_CODE}의 숫자와 <b>같아야 한다.</b> 테스트가 그 일치를 잠근다. */
    private static final int TOP_N = 1_000;

    /**
     * 한 콜에 넣는 심볼 수. 실측으로 8,495종목이 200개씩 43콜에 훑린다
     * ({@code alpaca-bars.md}). 후보만 물으므로 실제로는 그보다 적다.
     *
     * <p><b>ADR-0088 §버린 대안이 「여러 종목을 한 콜로 묶는다」를 기각해 뒀고, 그 근거 둘이
     * 여기에는 걸리지 않는다.</b> ① <i>실패 격리의 단위가 종목이 아니게 된다</i> —
     * {@code UniverseRebalanceJob}은 <b>일부러</b> 종목 격리를 쓰지 않는다(유니버스는 전체가
     * 하나의 정합적 스냅샷이고 부분 적용이 의미 없다). ② <i>모르는 심볼이 응답에서 조용히 빠져
     * 한 종목의 결측과 묶음 전체의 실패가 같은 모양이 된다</i> — {@link #MIN_CLOSE_COVERAGE}가
     * 그것을 막는다. 그 기각은 {@code PriceDataSource}에 걸린 것이고 이 어댑터는
     * {@code UniverseDataSource}다.
     */
    private static final int SYMBOLS_PER_CALL = 200;

    /**
     * 종가를 찾는 창(일). 실측으로 <b>창을 1일에서 10일로 넓혀도 결측이 안 준다</b> —
     * 남은 6은 무거래가 아니라 소스가 모르는 심볼이다({@code alpaca-bars.md}).
     * 그래도 10일을 쓰는 이유는 연휴가 낀 실행에서 마지막 세션을 놓치지 않기 위해서다.
     */
    private static final int CLOSE_WINDOW_DAYS = 10;

    /** 종가 조회 한 페이지의 상한. 200심볼 × 7세션 = 1,400 포인트로 이 안이다(실측). */
    private static final int PAGE_LIMIT = 10_000;

    /** 페이지 상한. 넘으면 부분 결과를 버린다({@code AlpacaPriceAdapter}와 같은 판단). */
    private static final int MAX_PAGES = 50;

    /**
     * {@code end}를 이만큼 당긴다. 15분 규칙에 걸리면 403이고 그 403은
     * {@link AlpacaResponse#kindOf}가 {@code AUTH}로 접어 <b>배치 전체를 죽인다.</b>
     */
    private static final Duration END_MARGIN = Duration.ofMinutes(16);

    /**
     * 주식수의 최신성 경계. 이보다 이른 기의 값은 쓰지 않는다.
     *
     * <p>{@code sec-edgar-financial.md}가 {@code companyconcept}에 요구한 가드와 같은 값이다 —
     * 거기서 {@code V}가 <b>2010-01-27</b>, {@code BRK.A}/{@code BRK.B}가 <b>2011-04-29</b>로
     * 멈춰 있었다. 경계가 없으면 십수 년 전 주식수가 오늘 종가에 곱해지고, 그 값은 결측이
     * 아니라 <b>몇 배씩 틀린 숫자</b>로 나온다.
     */
    private static final int SHARES_MAX_AGE_YEARS = 2;

    /**
     * 받은 종가가 후보 대비 이 비율에 못 미치면 부분 목록으로 본다(ADR-0008).
     *
     * <p>실측 결측은 8,495 중 <b>6</b>(0.07%)이고 창을 넓혀도 안 준다. 0.95는 그 층보다
     * 두 자릿수 느슨한 값이라 <b>정상 실행에서 울지 않는다</b> — 이 하한이 잡으려는 것은
     * 소수의 결측이 아니라 <b>묶음 조회가 통째로 조용히 빈 경우</b>다.
     */
    private static final double MIN_CLOSE_COVERAGE = 0.95;

    /**
     * 사업회사가 아닌 SIC. <b>코드로 판정한다</b>(ADR-0092의 체계 접두는 파티션 키의 것이고,
     * 여기는 미국 종목만 보므로 원문 4자리를 그대로 쓴다).
     *
     * <p>국내가 ADR-0012 §규칙에서 <i>"스팩·리츠·ETF를 제외한다 — 사업회사가 아니라 재무 지표가
     * 의미를 갖지 않는다"</i>고 정한 것과 대칭이다. ETF는 SIC가 없어(CIK에 안 붙는다) 애초에
     * 후보가 아니다.
     *
     * <p><b>MUST NOT: {@code 67xx} 전체로 넓히지 않는다.</b> 실측으로 그 division의 나머지에
     * {@code TPL}(Texas Pacific Land)·{@code RGLD}(Royal Gold)·{@code DLB}(Dolby)·
     * {@code IDCC}(InterDigital) 같은 <b>사업회사 18종목</b>이 들어 있다. ADR-0092가 지표
     * 판정불가 집합을 Division H 전체로 넓힌 것과 <b>다른 판단이고 그것이 의도다</b> —
     * 저쪽은 "틀린 값보다 답 없음"이지만 여기서 자르면 <b>화면에 아예 안 뜬다.</b>
     */
    private static final Set<String> NON_OPERATING_SIC = Set.of("6798", "6770");

    /**
     * 증권 종류를 가리키는 이름. <b>토큰 경계가 계약이다.</b>
     *
     * <p>IMPORTANT — 부분문자열로 쓰면 사업회사를 조용히 지운다. 실측(2026-08-31)으로 후보에서
     * <b>11종목</b>이 그렇게 잘렸고 7이 매출 1B 이상이었다: {@code NFLX}(N-<b>ETF</b>-LIX) ·
     * {@code CW}(Curtiss-W<b>RIGHT</b>) · {@code BFAM}·{@code BHF}·{@code BRSP}·{@code BTSG}·
     * {@code BV}(B<b>RIGHT</b>…) · {@code EDHL}(Ever<b>bright</b>) ·
     * {@code FUNC}(Firs<b>t Unit</b>ed). <b>넷플릭스가 빠지고 아무 에러도 안 난다.</b>
     *
     * <p><b>{@code TRUST}와 {@code UNIT}은 일부러 뺐다 — 실측이 그 둘을 기각했다.</b>
     * {@code TRUST}에 걸리는 후보 91 중 <b>74가 SIC 6798</b>이라 위 축이 이미 자르고, 남는
     * 것에 {@code NTRS}(Northern Trust — 은행)가 있다. {@code UNIT}에 걸리는 60 중
     * <b>48이 SIC 6770</b>이고, 남는 것이 {@code ET}(Energy Transfer 34.3B)·
     * {@code PAA}/{@code PAGP}(17.7B) 같은 <b>MLP 사업회사</b>다. 두 낱말이 자르려던 것은
     * 이미 SIC가 자르고, 남는 것은 오탐뿐이다.
     *
     * <p><b>MUST: 이 목록을 넓히기 전에 그 낱말의 한계 기여를 잰다</b> — 「SIC 축이 이미 자른
     * 것을 빼고 무엇이 남는가」와 「그중 사업회사가 무엇인가」 둘이다.
     * ({@code docs/phase-criteria.md} P26 축①)
     */
    private static final Pattern NON_COMMON_NAME = Pattern.compile(
            "\\bPREFERRED\\b|\\bPFD\\b|\\bWARRANTS?\\b|\\bRIGHTS?\\b|\\bDEPOSITARY\\b"
                    + "|\\bNOTES?\\b|\\bDEBENTURES?\\b|\\bBONDS?\\b|\\bETFS?\\b|\\bFUNDS?\\b");

    private final AlpacaUniverseAdapter listing;
    private final StoredShareCounts shareCounts;
    private final SecTickerCikCache cikCache;
    private final SecurityNameCache nameCache;
    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean credentialsPresent;
    private final int topN;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다
     * ({@code MarketCapUniverseAdapter}가 실측으로 남긴 함정이다).
     */
    @Autowired
    public UsMarketCapUniverseAdapter(AlpacaUniverseAdapter listing,
                                      StoredShareCounts shareCounts,
                                      SecTickerCikCache cikCache,
                                      SecurityNameCache nameCache,
                                      ExternalApiClientFactory clientFactory,
                                      DataSourceProperties properties,
                                      @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter) {
        this(listing, shareCounts, cikCache, nameCache, clientFactory, properties, rateLimiter,
                properties.getAlpacaMarketDataBaseUrl(), TOP_N);
    }

    /**
     * base URL과 상위 N을 바꿔 끼우는 생성자. 테스트 전용이다.
     *
     * <p>운영 N이 1,000이라 픽스처에 그만큼 넣지 않으면 <b>모든 시험이 부분 목록 거부로
     * 끝난다.</b> 선정 규칙은 N과 무관하므로 작은 N으로 검증한다
     * ({@code MarketCapUniverseAdapter}가 상위 N에 같은 장치를 둔 이유와 같다).
     */
    UsMarketCapUniverseAdapter(AlpacaUniverseAdapter listing,
                               StoredShareCounts shareCounts,
                               SecTickerCikCache cikCache,
                               SecurityNameCache nameCache,
                               ExternalApiClientFactory clientFactory,
                               DataSourceProperties properties,
                               RateLimiter rateLimiter,
                               String marketDataBaseUrl,
                               int topN) {
        DataSourceProperties.Source source = properties.getAlpaca();
        this.listing = listing;
        this.shareCounts = shareCounts;
        this.cikCache = cikCache;
        this.nameCache = nameCache;
        this.client = clientFactory.create(source, marketDataBaseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.credentialsPresent = source.hasApiKey() && source.hasSecretKey();
        this.topN = topN;
    }

    @Override
    public Set<String> supportedUniverseCodes() {
        return Set.of(UNIVERSE_CODE);
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public UniverseSnapshot fetchConstituents(String universeCode, LocalDate asOf)
            throws DataSourceException {
        if (!UNIVERSE_CODE.equals(universeCode)) {
            throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, SOURCE_NAME,
                    "담당하지 않는 유니버스다: " + universeCode);
        }
        requireCredentials();

        // 상장 목록은 소스에서 새로 받는다. US_LISTED 편입 원장을 쓰지 않는 이유는 그 유니버스가
        // 비활성이라 주간 스케줄러가 돌지 않고, 그러면 그 스냅샷이 마지막으로 갱신된 날짜가
        // 이 유니버스의 신선도를 조용히 정하게 되기 때문이다.
        List<AlpacaAsset> assets = listing.fetchActiveAssets();

        Map<String, BigDecimal> sharesByTicker = new HashMap<>();
        Exclusions excluded = new Exclusions();
        for (StoredShareCounts.ShareCount count :
                shareCounts.activeByMarket(UsMarket.CODE, asOf.minusYears(SHARES_MAX_AGE_YEARS), asOf)) {
            if (count.currencyMismatched()) {
                excluded.currency++;
                continue;
            }
            if (count.sicCode() != null && NON_OPERATING_SIC.contains(count.sicCode())) {
                excluded.nonOperating++;
                continue;
            }
            sharesByTicker.put(count.ticker(), count.shares());
        }

        List<AlpacaAsset> candidates = new ArrayList<>();
        for (AlpacaAsset asset : assets) {
            if (!sharesByTicker.containsKey(asset.symbol())) {
                excluded.noShares++;
                continue;
            }
            if (NON_COMMON_NAME.matcher(asset.name().toUpperCase(java.util.Locale.ROOT)).find()) {
                excluded.name++;
                continue;
            }
            candidates.add(asset);
        }
        if (candidates.size() < topN) {
            // MUST(ADR-0008): 부분 목록을 스냅샷으로 만들지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    ("후보가 %d종목뿐이라 상위 %d를 채우지 못한다 — 부분 목록을 반영하지 않는다. "
                            + "제외 내역: %s").formatted(candidates.size(), topN, excluded));
        }

        Map<String, BigDecimal> closes = fetchLatestCloses(candidates, asOf);
        double coverage = (double) closes.size() / candidates.size();
        if (coverage < MIN_CLOSE_COVERAGE) {
            // 모르는 심볼은 응답에서 조용히 빠진다(실측). 묶음 조회의 유일한 방어선이 이 하한이다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "종가가 온 후보가 %d/%d(%.1f%%)로 하한 %.0f%% 미만이다 — 부분 목록을 반영하지 않는다."
                            .formatted(closes.size(), candidates.size(), coverage * 100,
                                    MIN_CLOSE_COVERAGE * 100));
        }

        List<Ranked> ranked = new ArrayList<>(closes.size());
        for (AlpacaAsset asset : candidates) {
            BigDecimal close = closes.get(asset.symbol());
            if (close == null) {
                excluded.noClose++;
                continue;
            }
            ranked.add(new Ranked(asset, sharesByTicker.get(asset.symbol()).multiply(close)));
        }
        ranked.sort(Comparator.comparing(Ranked::marketCap).reversed()
                .thenComparing(r -> r.asset().symbol()));

        List<Ranked> deduped = dropCikDuplicates(ranked, excluded);
        if (deduped.size() < topN) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    ("CIK 중복을 걷고 나니 %d종목뿐이라 상위 %d를 채우지 못한다. 제외 내역: %s")
                            .formatted(deduped.size(), topN, excluded));
        }

        List<String> tickers = new ArrayList<>(topN);
        for (int i = 0; i < topN; i++) {
            AlpacaAsset asset = deduped.get(i).asset();
            // 이름과 거래소를 캐시에 심어 StockRegistrar가 종목마다 단건 조회를 부르지 않게 한다.
            nameCache.put(new SecurityDescriptor(
                    UsMarket.CODE, asset.symbol(), asset.name(),
                    null, null, null, null, asset.exchange()));
            tickers.add(asset.symbol());
        }

        log.info("미국 시총 상위 유니버스를 만들었다. {} 기준일={} 상장={} 후보={} 편입={} · 제외 {}",
                universeCode, asOf, assets.size(), candidates.size(), tickers.size(), excluded);
        logBoundary(deduped);
        return new UniverseSnapshot(universeCode, asOf, tickers, SOURCE_NAME);
    }

    /**
     * 같은 CIK를 나눠 쓰는 티커 중 시총이 가장 큰 하나만 남긴다.
     *
     * <p>우선주·baby bond·유닛이 <b>발행사의 주식수를 그대로 쓰므로</b> 같은 회사가 여러 번
     * 들어온다. 목록이 시총 내림차순이라 먼저 만난 것이 최대이고, 가격이 낮은 파생 증권은
     * 뒤에서 걸린다. <b>CIK를 모르는 티커는 남긴다</b> — 우리 8,504 중 1,815가 SEC 매핑 파일에
     * 없고 그것은 실패가 아니라 정상이다(ADR-0092 §규칙).
     */
    private List<Ranked> dropCikDuplicates(List<Ranked> ranked, Exclusions excluded)
            throws DataSourceException {
        Map<Long, String> keptByCik = new HashMap<>();
        List<Ranked> kept = new ArrayList<>(ranked.size());
        for (Ranked row : ranked) {
            Optional<Long> cik = cikCache.findCik(row.asset().symbol());
            if (cik.isEmpty()) {
                kept.add(row);
                continue;
            }
            String winner = keptByCik.putIfAbsent(cik.get(), row.asset().symbol());
            if (winner == null) {
                kept.add(row);
            } else {
                excluded.cikDuplicate++;
                log.debug("CIK 중복이라 뺀다: {} (같은 CIK {}를 {}가 먼저 차지했다)",
                        row.asset().symbol(), cik.get(), winner);
            }
        }
        return kept;
    }

    /**
     * 경계 구간을 로그로 남긴다. <b>밴드를 실제로 넓히지 않는다</b> —
     * {@code MarketCapUniverseAdapter.select}와 같은 판단이다. 넓히면 「N종목 유니버스」라는
     * 정의 자체가 흐려지고, 실제 유지 판정은 {@code UniverseReconciler}가 집합 차집합으로 한다.
     */
    private void logBoundary(List<Ranked> deduped) {
        int end = Math.min(deduped.size(), topN + 20);
        if (end > topN) {
            log.debug("경계 구간({}~{}위): {}", topN + 1, end,
                    deduped.subList(topN, end).stream().map(r -> r.asset().symbol()).toList());
        }
    }

    /**
     * 후보의 최근 종가. 심볼을 묶어 묻고 <b>심볼마다 마지막 봉</b>을 쓴다.
     *
     * <p>{@code adjustment=raw}인 것이 계약이다 — 소스가 계산한 수정주가를 받지 않는다
     * (ADR-0006). 순위에는 <b>보정하지 않은 오늘의 시장가</b>가 맞다.
     */
    private Map<String, BigDecimal> fetchLatestCloses(List<AlpacaAsset> candidates, LocalDate asOf)
            throws DataSourceException {
        LocalDate from = asOf.minusDays(CLOSE_WINDOW_DAYS);
        Instant end = clampEnd(asOf);
        Map<String, BigDecimal> closes = new LinkedHashMap<>();

        for (int offset = 0; offset < candidates.size(); offset += SYMBOLS_PER_CALL) {
            List<AlpacaAsset> chunk =
                    candidates.subList(offset, Math.min(candidates.size(), offset + SYMBOLS_PER_CALL));
            String symbols = String.join(",", chunk.stream().map(AlpacaAsset::symbol).toList());
            String pageToken = null;
            int page = 0;
            do {
                String context = "closes %d~%d/%d page=%d"
                        .formatted(offset + 1, offset + chunk.size(), candidates.size(), page + 1);
                ResponseEntity<String> response = call(symbols, from, end, pageToken, context);
                AlpacaResponse.Envelope envelope = AlpacaResponse.requireBars(
                        response.getStatusCode(), response.getBody(), SOURCE_NAME, context);
                envelope.value().properties().forEach(entry -> {
                    // 정렬이 asc라 마지막 원소가 가장 늦은 봉이다. 페이지가 갈려도 뒤 페이지가
                    // 언제나 더 늦으므로 덮어쓰기가 옳다.
                    for (JsonNode bar : entry.getValue()) {
                        BigDecimal close = bar.path("c").decimalValue();
                        if (close != null && close.signum() > 0) {
                            closes.put(entry.getKey(), close);
                        }
                    }
                });
                pageToken = envelope.nextPageToken();
                page++;
                if (page >= MAX_PAGES && pageToken != null) {
                    throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                            "페이지가 %d를 넘는다 — 부분 결과를 버린다. %s".formatted(MAX_PAGES, context));
                }
            } while (pageToken != null);
        }
        return closes;
    }

    /** {@code AlpacaPriceAdapter.clampEnd}와 같은 계약이다 — 15분 규칙을 구조적으로 못 넘는다. */
    private static Instant clampEnd(LocalDate asOf) {
        Instant requested = asOf.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant safe = Instant.now().minus(END_MARGIN);
        return requested.isBefore(safe) ? requested : safe;
    }

    private ResponseEntity<String> call(String symbols, LocalDate from, Instant end,
                                        String pageToken, String context)
            throws DataSourceException {
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
                        if (pageToken != null) {
                            uri.queryParam("page_token", pageToken);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    // MUST: 문자열로 받는다 — 오류 본문이 JSON이 아닌 엔드포인트가 있다.
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT, SOURCE_NAME,
                    "호출에 실패했다. " + context, e);
        }
    }

    private void requireCredentials() throws DataSourceException {
        if (!credentialsPresent) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, SOURCE_NAME,
                    "APCA_API_KEY_ID와 APCA_API_SECRET_KEY가 둘 다 설정되어야 한다.");
        }
    }

    private record Ranked(AlpacaAsset asset, BigDecimal marketCap) {
    }

    /**
     * 축별 제외 수. <b>합계만 남기지 않는 것이 계약이다</b> — 합계로는 오분류와 정상 배제가
     * 구분되지 않는다({@code docs/phase-criteria.md} P26 축①).
     */
    private static final class Exclusions {
        private int currency;
        private int nonOperating;
        private int noShares;
        private int name;
        private int noClose;
        private int cikDuplicate;

        @Override
        public String toString() {
            return "통화=%d · REIT/SPAC=%d · 주식수없음=%d · 이름=%d · 종가없음=%d · CIK중복=%d"
                    .formatted(currency, nonOperating, noShares, name, noClose, cikDuplicate);
        }
    }
}
