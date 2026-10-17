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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
     * 「숨은 상장」으로 받을 SEC 거래소 표기(ADR-0171). SEC는 NYSE American·Arca도 「NYSE」로 적으므로
     * 이것만으로는 거래소를 확정하지 못한다 — 생존자마다 {@link #LISTED_EXCHANGES}로 시세 소스 단건을 다시 본다.
     */
    private static final Set<String> SEC_LISTED_LABELS = Set.of("NYSE", "Nasdaq");

    /** 시세 소스 단건 응답에서 상장으로 받는 거래소. 목록 조회의 {@code exchange} 인자와 같다(ADR-0086). */
    private static final Set<String> LISTED_EXCHANGES = Set.of("NYSE", "NASDAQ");

    /**
     * 숨은 생존자 상한. 2026-10-02 실측 생존 10의 20배다 — 넘으면 시세 소스 목록이 거래 중인 상장의 2% 넘게를
     * 잃은 것이라 목록 자체가 깨진 것으로 본다(ADR-0008). 단건을 그만큼 부르는 것이 아니라 <b>멈춘다.</b>
     */
    private static final int HIDDEN_SURVIVOR_LIMIT = 200;

    /** 봉 시각({@code t})을 거래일로 바꾸는 시간대. 일봉의 {@code t}는 그 날 미국 동부 자정이다. */
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

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
     *
     * <p><b>혼성 증권 문구 넷</b>({@code CORPORATE UNITS} · {@code EQUITY UNITS} · {@code PREFERENCE} ·
     * {@code CAPITAL SECURITIES})의 한계 기여(2026-10-05 · 미국 상장 이름): 앞 둘에 9
     * ({@code PPLC}·{@code DUKU}·{@code SOMN}·{@code NEE.PRS/PRT/PRV}·{@code BTSGU}·{@code NOVTU}·{@code VSECU}),
     * 뒤 둘에 2({@code LILAP}·{@code DDT}) — <b>전부 사업회사 보통주가 아니다.</b> 이것들은 발행사 주식수를 그대로
     * 써서 시총이 부풀고, 보통주보다 비싸면 CIK 대표를 빼앗았다({@code PPLC}가 {@code PPL}을 밀어냄).
     * 대표는 {@link #dropCikDuplicates}가 거래대금으로 고르므로 이 문구는 <b>보통주가 후보가 아닐 때의 방어</b>다.
     * {@code DEPOSITORY}는 안 넣는다 — 그 오철자를 쓰는 ADR 보통주({@code CMPS}·{@code SLN})를 자른다.
     */
    private static final String SECURITY_KIND_WORDS =
            "\\bPREFERRED\\b|\\bPFD\\b|\\bWARRANTS?\\b|\\bRIGHTS?\\b|\\bDEPOSITARY\\b"
                    + "|\\bNOTES?\\b|\\bDEBENTURES?\\b|\\bBONDS?\\b|\\bETFS?\\b|\\bFUNDS?\\b";

    private static final String HYBRID_WORDS =
            "\\bCORPORATE UNITS?\\b|\\bEQUITY UNITS?\\b|\\bPREFERENCE\\b|\\bCAPITAL SECURITIES\\b";

    private static final Pattern NON_COMMON_NAME = Pattern.compile(SECURITY_KIND_WORDS + "|" + HYBRID_WORDS);

    /** 혼성 문구에<b>만</b> 걸린 이름을 가려 로그로 남긴다 — 이 문구의 한계 기여를 실행마다 다시 잴 재료다. */
    private static final Pattern SECURITY_KIND_ONLY = Pattern.compile(SECURITY_KIND_WORDS);

    private final AlpacaUniverseAdapter listing;
    private final StoredShareCounts shareCounts;
    private final SecTickerCikCache cikCache;
    private final SecurityNameCache nameCache;
    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final boolean credentialsPresent;
    private final int topN;
    /** 미국 관문 설정 — 켜졌으면 주식수 클래스 축의 편입 보류·발행사 제외를 순위에 건다({@link #applyClassHolds}). */
    private final boolean usGate;

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
                                      @Qualifier("alpacaRateLimiter") RateLimiter rateLimiter,
                                      @Value("${stockscreener.gate.us}") boolean usGate) {
        this(listing, shareCounts, cikCache, nameCache, clientFactory, properties, rateLimiter,
                properties.getAlpacaMarketDataBaseUrl(), TOP_N, usGate);
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
        this(listing, shareCounts, cikCache, nameCache, clientFactory, properties, rateLimiter, marketDataBaseUrl, topN, false);
    }

    UsMarketCapUniverseAdapter(AlpacaUniverseAdapter listing,
                               StoredShareCounts shareCounts,
                               SecTickerCikCache cikCache,
                               SecurityNameCache nameCache,
                               ExternalApiClientFactory clientFactory,
                               DataSourceProperties properties,
                               RateLimiter rateLimiter,
                               String marketDataBaseUrl,
                               int topN,
                               boolean usGate) {
        DataSourceProperties.Source source = properties.getAlpaca();
        this.listing = listing;
        this.shareCounts = shareCounts;
        this.cikCache = cikCache;
        this.nameCache = nameCache;
        this.client = clientFactory.create(source, marketDataBaseUrl, AlpacaHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.credentialsPresent = source.hasApiKey() && source.hasSecretKey();
        this.topN = topN;
        this.usGate = usGate;
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
        return fetchConstituents(universeCode, asOf, Set.of());
    }

    @Override
    public UniverseSnapshot fetchConstituents(String universeCode, LocalDate asOf, Set<String> previous)
            throws DataSourceException {
        Ranking ranking = rank(universeCode, asOf, previous);
        List<String> tickers = new ArrayList<>(topN);
        for (int i = 0; i < topN; i++) {
            AlpacaAsset asset = ranking.deduped().get(i).asset();
            // 이름과 거래소를 캐시에 심어 StockRegistrar가 종목마다 단건 조회를 부르지 않게 한다.
            nameCache.put(new SecurityDescriptor(
                    UsMarket.CODE, asset.symbol(), asset.name(),
                    null, null, null, null, asset.exchange()));
            tickers.add(asset.symbol());
        }

        log.info("미국 시총 상위 유니버스를 만들었다. {} 기준일={} 상장={} 후보={} 편입={} · 제외 {}",
                universeCode, asOf, ranking.listed(), ranking.candidates(), tickers.size(), ranking.excluded());
        logBoundary(ranking.deduped());
        return new UniverseSnapshot(universeCode, asOf, tickers, SOURCE_NAME);
    }

    /**
     * 모든 거름을 통과한 후보의 시총 순위(CIK 대표만). 상위 N을 자르기 전이다 — 명부 재생(클래스 축 드라이런 2)이
     * 경계 너머의 순위를 읽는 자리이고, {@link #fetchConstituents}는 이것의 앞 N이다.
     */
    Ranking rank(String universeCode, LocalDate asOf) throws DataSourceException {
        return rank(universeCode, asOf, Set.of());
    }

    Ranking rank(String universeCode, LocalDate asOf, Set<String> previous) throws DataSourceException {
        if (!UNIVERSE_CODE.equals(universeCode)) {
            throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, SOURCE_NAME,
                    "담당하지 않는 유니버스다: " + universeCode);
        }
        requireCredentials();

        // 상장 목록은 소스에서 새로 받는다. US_LISTED 편입 원장을 쓰지 않는 이유는 그 유니버스가
        // 비활성이라 주간 스케줄러가 돌지 않고, 그러면 그 스냅샷이 마지막으로 갱신된 날짜가
        // 이 유니버스의 신선도를 조용히 정하게 되기 때문이다.
        // 이 목록은 상장 사실이 아니다 — 빠뜨린 상장은 아래 「숨은 상장」이 보완한다(ADR-0171).
        List<AlpacaAsset> assets = listing.fetchActiveAssets();
        // MUST: 이 호출 안에서 SEC 판 하나만 쓴다 — 숨은 상장의 거래소와 CIK 중복 제거가 같은 판을 본다(ADR-0171).
        SecTickerCikCache.Snapshot sec = cikCache.freshSnapshot();
        Set<String> listed = new HashSet<>();
        for (AlpacaAsset asset : assets) {
            listed.add(asset.symbol());
        }

        Map<String, BigDecimal> sharesByTicker = new HashMap<>();
        Map<String, String> storedNames = new HashMap<>();
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
            storedNames.put(count.ticker(), count.name());
        }
        if (usGate) {
            applyClassHolds(asOf, previous, sharesByTicker, storedNames, excluded);
        }

        List<AlpacaAsset> candidates = new ArrayList<>();
        for (AlpacaAsset asset : assets) {
            if (!sharesByTicker.containsKey(asset.symbol())) {
                excluded.noShares++;
                continue;
            }
            if (excludedByName(asset.symbol(), asset.name(), excluded)) {
                continue;
            }
            candidates.add(asset);
        }
        List<AlpacaAsset> hiddenPool = hiddenPool(sharesByTicker, storedNames, listed, sec, excluded);
        if (candidates.size() < topN) {
            // MUST(ADR-0008): 부분 목록을 스냅샷으로 만들지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    ("후보가 %d종목뿐이라 상위 %d를 채우지 못한다 — 부분 목록을 반영하지 않는다. "
                            + "제외 내역: %s").formatted(candidates.size(), topN, excluded));
        }

        List<AlpacaAsset> quoted = new ArrayList<>(candidates.size() + hiddenPool.size());
        quoted.addAll(candidates);
        quoted.addAll(hiddenPool);
        Map<String, Quote> closes = fetchLatestCloses(quoted, asOf);
        long listedQuoted = candidates.stream().filter(a -> closes.containsKey(a.symbol())).count();
        double coverage = (double) listedQuoted / candidates.size();
        if (coverage < MIN_CLOSE_COVERAGE) {
            // 모르는 심볼은 응답에서 조용히 빠진다(실측). 묶음 조회의 유일한 방어선이 이 하한이다.
            // 목록 후보로만 잰다 — 숨은 풀은 봉이 끊긴 폐지 티커가 섞여 있는 것이 정상이다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "종가가 온 후보가 %d/%d(%.1f%%)로 하한 %.0f%% 미만이다 — 부분 목록을 반영하지 않는다."
                            .formatted(listedQuoted, candidates.size(), coverage * 100,
                                    MIN_CLOSE_COVERAGE * 100));
        }
        // MUST: 숨은 상장 판정을 CIK 중복 제거 앞에 둔다 — 뒤면 거래대금 큰 낡은 숨은 티커가 대표를 차지한 뒤
        // 빠져 같은 CIK의 산 클래스까지 사라진다.
        candidates.addAll(acceptHidden(hiddenPool, closes, storedNames, listed, sec, excluded));

        List<Ranked> ranked = new ArrayList<>(closes.size());
        for (AlpacaAsset asset : candidates) {
            Quote quote = closes.get(asset.symbol());
            if (quote == null) {
                excluded.noClose++;
                continue;
            }
            ranked.add(new Ranked(asset, sharesByTicker.get(asset.symbol()).multiply(quote.close()),
                    quote.dollarVolume()));
        }
        ranked.sort(Comparator.comparing(Ranked::marketCap).reversed()
                .thenComparing(r -> r.asset().symbol()));

        List<Ranked> deduped = dropCikDuplicates(ranked, excluded, sec);
        if (deduped.size() < topN) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    ("CIK 중복을 걷고 나니 %d종목뿐이라 상위 %d를 채우지 못한다. 제외 내역: %s")
                            .formatted(deduped.size(), topN, excluded));
        }
        return new Ranking(assets.size(), candidates.size(), deduped, excluded);
    }

    /**
     * 주식수 클래스 축의 편입 보류와 발행사 제외(관문 뒤 · 탐색 {@code SHARES-CLASS-PLAN-V5-1015.md} §10b-1·§10e · 규칙은
     * {@link com.stockscreener.pipeline.datasource.us.shareclass.MembershipHold}).
     *
     * <ul>
     *   <li>그날 최신 판정이 숨김이고 그 앞이 보였으며 119일 안({@code HELD})인 <b>앞 편입</b>은 <b>마지막으로 보인 판정의 N_L</b>
     *       (그날까지의 분할 반영)으로 후보에 든다 — 자리를 미리 떼지 않고 그 값으로 순위를 다툰다. 나머지 거름(통화 · 업종 ·
     *       이름 · 종가 · CIK 대표)은 다른 후보와 똑같이 탄다.</li>
     *   <li>숨김인데 보류가 아닌 줄(연속 숨김 · 첫 판 숨김 · 상한 넘김 · 앞 편입 아님)은 판독기가 이미 안 낸다 — 후보가 못 된다.</li>
     *   <li>발행사 제외({@code EXCLUDED})는 그날 보이는 판이 있어도 뺀다.</li>
     * </ul>
     */
    private void applyClassHolds(LocalDate asOf, Set<String> previous, Map<String, BigDecimal> sharesByTicker,
                                 Map<String, String> storedNames, Exclusions excluded) {
        List<String> held = new ArrayList<>();
        List<String> classExcluded = new ArrayList<>();
        for (StoredShareCounts.ClassHold hold : shareCounts.classHolds(UsMarket.CODE, asOf).values()) {
            switch (hold.state()) {
                case EXCLUDED -> {
                    sharesByTicker.remove(hold.ticker());
                    classExcluded.add(hold.ticker());
                }
                case HELD -> {
                    if (!previous.contains(hold.ticker()) || hold.heldShares() == null || hold.heldShares().signum() <= 0) {
                        continue;
                    }
                    if (hold.currencyMismatched()) {
                        excluded.currency++;
                        continue;
                    }
                    if (hold.sicCode() != null && NON_OPERATING_SIC.contains(hold.sicCode())) {
                        excluded.nonOperating++;
                        continue;
                    }
                    sharesByTicker.put(hold.ticker(), hold.heldShares());
                    storedNames.put(hold.ticker(), hold.name());
                    held.add(hold.ticker());
                }
                default -> {
                    // 연속 숨김 · 첫 판 숨김 · 상한 넘김은 판독기가 이미 안 냈다 — 후보가 못 된다.
                }
            }
        }
        // 리밸런싱마다 이 줄이 보류의 귀속 재료다(수용 기준 7·9).
        log.info("클래스 축 보류. 기준일={} 앞편입={} 보류후보={}{} 발행사제외={}{}", asOf, previous.size(), held.size(), held,
                classExcluded.size(), classExcluded);
    }

    /** 순위와 그것을 만든 실행의 귀속 재료(상장 · 후보 수 · 축별 제외). */
    record Ranking(int listed, int candidates, List<Ranked> deduped, Exclusions excluded) {
    }

    /**
     * 같은 CIK를 나눠 쓰는 티커 중 <b>창 안 거래대금이 가장 큰</b> 하나만 남긴다(ADR-0169).
     *
     * <p>우선주·baby bond·유닛·의결권 클래스가 <b>발행사의 주식수를 그대로 쓰므로</b> 같은 회사가 여러 번
     * 들어온다. 그 티커들끼리 「주식수 × 종가」는 사실상 <b>가격 순위</b>라, 예전처럼 시총 최대를 고르면
     * <b>가장 비싼 증권</b>이 회사를 대표했다 — 거래 드문 클래스({@code MOG.B} 0.09 대 {@code MOG.A} 106백만
     * 달러 · {@code TAP.A}·{@code WSO.B}·{@code BIO.B}·{@code LBTYB})와 혼성 단위({@code PPLC}·{@code BTSGU})가
     * 그렇게 명부에 들었고, 시계열이 평봉으로 찼다(2026-10-05 실측). 거래대금은 시장이 그 회사의 지분을
     * 실제로 거래하는 증권을 가리키고, 정렬 창 약 250거래일에서 대표가 쌍마다 0~1회만 바뀐다(가격 규칙은 12~100회).
     *
     * <p>{@code close × volume}은 장외 거래량 탓에 절대값이 계통 과대다({@code alpaca-bars.md} — 중앙 약 13%).
     * 그 기각은 <b>절대값을 국내 거래대금과 견줄 때</b>의 것이고 같은 발행사 클래스끼리의 서열에서는 과대가 양쪽에
     * 같은 방향으로 걸린다 — 서열 격차가 가장 좁은 쌍이 {@code Z}/{@code ZG} 0.899다(클래스별 장외 비중 차는 재지 않았다).
     *
     * <p>동률이면 시총 → 기호 순이다. 대표는 자기 시총 자리에 그대로 남는다. <b>CIK를 모르는 티커는 남긴다</b> —
     * 우리 8,504 중 1,815가 SEC 매핑 파일에 없고 그것은 실패가 아니라 정상이다(ADR-0092 §규칙).
     */
    private List<Ranked> dropCikDuplicates(List<Ranked> ranked, Exclusions excluded,
                                           SecTickerCikCache.Snapshot sec) {
        Map<Long, List<Ranked>> byCik = new LinkedHashMap<>();
        Map<Ranked, Long> cikOf = new HashMap<>();
        for (Ranked row : ranked) {
            Optional<Long> cik = sec.findCik(row.asset().symbol());
            if (cik.isPresent()) {
                byCik.computeIfAbsent(cik.get(), k -> new ArrayList<>()).add(row);
                cikOf.put(row, cik.get());
            }
        }
        Map<Ranked, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            rankOf.put(ranked.get(i), i + 1);
        }
        Map<Long, Ranked> representative = new HashMap<>();
        byCik.forEach((cik, rows) -> {
            Ranked best = rows.stream().max(Comparator.comparing(Ranked::dollarVolume)
                    .thenComparing(Ranked::marketCap)
                    .thenComparing(r -> r.asset().symbol(), Comparator.reverseOrder())).orElseThrow();
            representative.put(cik, best);
            // 시총 내림차순 목록에서 먼저 만난 것이 옛 규칙(시총 최대)의 승자다.
            Ranked byMarketCap = rows.get(0);
            if (byMarketCap != best) {
                // 리밸런싱마다 이 줄이 명부 차이의 귀속 재료다 — 옛 규칙과 갈린 CIK만 찍는다.
                // 순위는 CIK 중복 제거 전 후보 목록의 시총 순위다 — 혼성 단위는 이름 축에서 먼저 빠져 이 줄에 안 뜬다(제외 내역의 혼성 목록).
                log.info("CIK 대표가 시총 최대와 갈렸다: cik={} 대표={}(중복 제거 전 시총 {}위 · 거래대금 {}) 시총 최대={}({}위 · 거래대금 {})",
                        cik, best.asset().symbol(), rankOf.get(best), best.dollarVolume().toBigInteger(),
                        byMarketCap.asset().symbol(), rankOf.get(byMarketCap),
                        byMarketCap.dollarVolume().toBigInteger());
            }
            if (rows.size() > 1) {
                // 드라이런·운영 대조의 재료다 — 대표가 왜 그 증권인지가 실행마다 남아야 다음 실행과 견줄 수 있다.
                log.info("CIK 대표: cik={} 대표={} 후보={}", cik, best.asset().symbol(),
                        rows.stream().map(r -> r.asset().symbol() + "=" + r.dollarVolume().toBigInteger())
                                .toList());
            }
        });
        List<Ranked> kept = new ArrayList<>(ranked.size());
        for (Ranked row : ranked) {
            Long cik = cikOf.get(row);
            if (cik == null || representative.get(cik) == row) {
                kept.add(row);
            } else {
                excluded.cikDuplicate++;
            }
        }
        return kept;
    }

    /** 이름 축의 술어 — 증권 종류 낱말 · 혼성 문구에 걸리면 보통주가 아니다. 상장 판정의 「등록인의 보통주 행」도 이것으로 센다. */
    static boolean nonCommonName(String name) {
        return name != null && NON_COMMON_NAME.matcher(name.toUpperCase(java.util.Locale.ROOT)).find();
    }

    /**
     * 이름 축. 걸리면 세고 참을 낸다 — 목록 후보와 숨은 상장이 같은 축을 탄다.
     */
    private static boolean excludedByName(String symbol, String name, Exclusions excluded) {
        if (!nonCommonName(name)) {
            return false;
        }
        String upperName = name.toUpperCase(java.util.Locale.ROOT);
        excluded.name++;
        if (!SECURITY_KIND_ONLY.matcher(upperName).find()) {
            excluded.hybridOnly.add(symbol);
        }
        return true;
    }

    /**
     * 시세 소스 목록이 빠뜨린 상장 후보(ADR-0171). <b>그 목록은 상장 사실이 아니다</b> — 단건 조회가 아는 자산
     * 일부(ET·DDS·SUN …)를 상태 인자와 무관하게 뺀다(2026-10-05 실측 · 전 상태 33,550건에도 없음).
     *
     * <p>풀 = 우리 주식수가 있고(통화·SIC 축 뒤) SEC가 NYSE·Nasdaq로 적었는데 목록에 없는 티커. 이름은 저장된
     * 이름으로 이름 축을 먼저 태운다(0콜). 살아 있는지는 여기서 안 본다 — {@link #acceptHidden}이 봉으로 본다.
     */
    private static List<AlpacaAsset> hiddenPool(Map<String, BigDecimal> sharesByTicker,
                                                Map<String, String> storedNames, Set<String> listed,
                                                SecTickerCikCache.Snapshot sec, Exclusions excluded) {
        List<AlpacaAsset> pool = new ArrayList<>();
        for (String ticker : new TreeMap<>(sharesByTicker).keySet()) {
            if (listed.contains(ticker)
                    || !SEC_LISTED_LABELS.contains(sec.exchangeOf(ticker).orElse(""))) {
                continue;
            }
            String stored = storedNames.getOrDefault(ticker, "");
            if (excludedByName(ticker, stored == null ? "" : stored, excluded)) {
                continue;
            }
            pool.add(new AlpacaAsset(ticker, stored == null ? "" : stored, "", "", false));
        }
        return pool;
    }

    /**
     * 숨은 풀에서 받을 것을 고른다. 차례가 계약이다.
     * <ol>
     *   <li><b>마지막 세션에 봉이 있어야 한다</b> — SEC 파일은 폐지·개명을 늦게 반영하고(DBRG 마지막 봉 09-29 ·
     *       DOMO 09-23인데 SEC엔 남음) 시세 소스 단건의 {@code status}는 ET와 폐지 종목 둘 다 {@code inactive}라
     *       살아 있음의 증거가 봉뿐이다.</li>
     *   <li>생존자가 {@link #HIDDEN_SURVIVOR_LIMIT}를 넘으면 목록이 깨진 것으로 보고 멈춘다.</li>
     *   <li><b>단건으로 거래소를 확인한다</b> — NYSE·NASDAQ만. 404는 빼고, 2xx 아닌 나머지는 던진다(빼고 넘어가면
     *       원래 결함이 재현된다).</li>
     *   <li>이름은 단건 이름(증권 수준)으로 이름 축을 다시 — 비면 저장된 이름.</li>
     * </ol>
     * <p><b>개명 판정은 하지 않는다</b> — 같은 CIK의 다른 티커가 목록에 있는지는 귀속용 라벨로만 남긴다. 그 목록이
     * 상장 사실이 아니라고 판정해 놓고 개명의 증거로 다시 쓰면, 보통주만 빠지고 우선주가 남은 발행사에서 원래
     * 결함이 그대로 재현된다(현 편입 45가 그 모양).
     */
    private List<AlpacaAsset> acceptHidden(List<AlpacaAsset> pool, Map<String, Quote> closes,
                                           Map<String, String> storedNames, Set<String> listed,
                                           SecTickerCikCache.Snapshot sec, Exclusions excluded)
            throws DataSourceException {
        if (pool.isEmpty()) {
            return List.of();
        }
        LocalDate lastSession = majoritySession(closes.values());
        List<AlpacaAsset> survivors = new ArrayList<>();
        for (AlpacaAsset asset : pool) {
            Quote quote = closes.get(asset.symbol());
            if (quote != null && quote.lastDate().equals(lastSession)) {
                survivors.add(asset);
            } else {
                excluded.hiddenStale.add(asset.symbol() + "(" + (quote == null ? "봉없음" : quote.lastDate()) + ")");
            }
        }
        if (survivors.size() > HIDDEN_SURVIVOR_LIMIT) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    ("목록 밖인데 마지막 세션에 봉이 있는 상장이 %d종목으로 상한 %d를 넘는다 — 시세 소스 목록이 "
                            + "깨진 것으로 보고 반영하지 않는다(ADR-0008).").formatted(survivors.size(), HIDDEN_SURVIVOR_LIMIT));
        }
        Set<Long> listedCiks = new HashSet<>();
        for (String symbol : listed) {
            sec.findCik(symbol).ifPresent(listedCiks::add);
        }
        List<AlpacaAsset> accepted = new ArrayList<>();
        for (AlpacaAsset survivor : survivors) {
            String symbol = survivor.symbol();
            Optional<AlpacaAsset> single = listing.fetchAsset(symbol);
            if (single.isEmpty()) {
                excluded.hiddenUnknown.add(symbol);
                continue;
            }
            AlpacaAsset found = single.get();
            if (!LISTED_EXCHANGES.contains(found.exchange())) {
                excluded.hiddenOffExchange.add(symbol + ":" + found.exchange());
                continue;
            }
            String name = found.name().isBlank() ? storedNames.getOrDefault(symbol, "") : found.name();
            if (excludedByName(symbol, name, excluded)) {
                continue;
            }
            accepted.add(new AlpacaAsset(symbol, name, found.exchange(), found.status(), found.tradable()));
            boolean sharesCik = sec.findCik(symbol).map(listedCiks::contains).orElse(false);
            excluded.hiddenAccepted.add(symbol + "(" + found.status() + " · " + found.exchange()
                    + (sharesCik ? " · 같은 CIK 목록에 있음" : "") + ")");
        }
        // 귀속 재료다 — 리밸런싱마다 목록이 무엇을 빠뜨렸고 그중 무엇을 받았는지가 남아야 명부 차이를 설명한다.
        log.info("숨은 상장: 풀={} 마지막 세션={} 받음={} 마지막 세션 봉 없음={} 단건 없음={} 거래소 밖={}",
                pool.size(), lastSession, excluded.hiddenAccepted, excluded.hiddenStale,
                excluded.hiddenUnknown, excluded.hiddenOffExchange);
        return accepted;
    }

    /**
     * 후보 과반이 닿은 가장 늦은 거래일. 최댓값이 아닌 이유는 이상봉 하나가 숨은 상장 전체를 떨어뜨리지
     * 않게 하려는 것이다.
     */
    static LocalDate majoritySession(Collection<Quote> quotes) {
        List<LocalDate> dates = quotes.stream().map(Quote::lastDate)
                .sorted(Comparator.reverseOrder()).toList();
        return dates.get(dates.size() / 2);
    }

    /**
     * 경계 구간을 로그로 남긴다. <b>밴드를 실제로 넓히지 않는다</b> —
     * {@code MarketCapUniverseAdapter.select}와 같은 판단이다. 넓히면 「N종목 유니버스」라는
     * 정의 자체가 흐려지고, 실제 유지 판정은 {@code UniverseReconciler}가 집합 차집합으로 한다.
     */
    private void logBoundary(List<Ranked> deduped) {
        // INFO다 — 명부 차이의 「경계 밀림」을 귀속하려면 경계 양쪽의 이름과 시총이 실행마다 남아야 한다.
        int from = Math.max(0, topN - 5);
        int end = Math.min(deduped.size(), topN + 20);
        if (end > from) {
            log.info("경계 구간({}~{}위 · {}위까지 편입): {}", from + 1, end, topN,
                    deduped.subList(from, end).stream()
                            .map(r -> r.asset().symbol() + "=" + r.marketCap().toBigInteger()).toList());
        }
    }

    /**
     * 후보마다 창 안 마지막 종가와 <b>창 안 거래대금 합</b>({@code Σ c × v})을 낸다. 심볼을 묶어 묻는다.
     *
     * <p>{@code adjustment=raw}인 것이 계약이다 — 소스가 계산한 수정주가를 받지 않는다
     * (ADR-0006). 순위에는 <b>보정하지 않은 오늘의 시장가</b>가 맞다.
     *
     * <p><b>합이다 — 평균이 아니다.</b> 거래 드문 클래스는 봉이 띄엄띄엄이라 있는 봉만 평균하면 희소한 쪽이
     * 부푼다(실측 {@code WSO.B} 14세션 중 체결 봉 2). <b>페이지가 갈려도 누적한다</b> — 다중 심볼 응답은 심볼 우선
     * 정렬이라 한 심볼의 봉이 두 페이지에 걸칠 수 있다.
     *
     * <p>MUST: 거래량({@code v})이 없는 봉은 MALFORMED로 세운다. 0으로 읽으면 전부 동률이 되어 대표가 기호순으로
     * <b>조용히</b> 정해진다.
     */
    private Map<String, Quote> fetchLatestCloses(List<AlpacaAsset> candidates, LocalDate asOf)
            throws DataSourceException {
        LocalDate from = asOf.minusDays(CLOSE_WINDOW_DAYS);
        Instant end = clampEnd(asOf);
        Map<String, BigDecimal> closes = new LinkedHashMap<>();
        Map<String, LocalDate> lastDates = new HashMap<>();
        Map<String, BigDecimal> dollarVolumes = new HashMap<>();

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
                for (Map.Entry<String, JsonNode> entry : envelope.value().properties()) {
                    // 정렬이 asc라 마지막 원소가 가장 늦은 봉이다. 페이지가 갈려도 뒤 페이지가
                    // 언제나 더 늦으므로 종가는 덮어쓰기가 옳다 — 거래대금은 누적한다.
                    for (JsonNode bar : entry.getValue()) {
                        if (!bar.path("v").isNumber()) {
                            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                                    "봉에 거래량(v)이 없다 — 0으로 읽으면 CIK 대표가 기호순으로 정해진다. symbol=%s %s"
                                            .formatted(entry.getKey(), context));
                        }
                        BigDecimal close = bar.path("c").decimalValue();
                        if (close != null && close.signum() > 0) {
                            closes.put(entry.getKey(), close);
                            lastDates.put(entry.getKey(), barDate(bar, entry.getKey(), context));
                            dollarVolumes.merge(entry.getKey(),
                                    close.multiply(bar.path("v").decimalValue()), BigDecimal::add);
                        }
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
        Map<String, Quote> quotes = new LinkedHashMap<>();
        closes.forEach((symbol, close) ->
                quotes.put(symbol, new Quote(close, dollarVolumes.getOrDefault(symbol, BigDecimal.ZERO),
                        lastDates.get(symbol))));
        return quotes;
    }

    private static LocalDate barDate(JsonNode bar, String symbol, String context) throws DataSourceException {
        try {
            return Instant.parse(bar.path("t").asString("")).atZone(MARKET_ZONE).toLocalDate();
        } catch (java.time.format.DateTimeParseException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "봉 시각(t)을 읽지 못했다. symbol=%s %s".formatted(symbol, context), e);
        }
    }

    /** 창 안 마지막 종가 · 창 안 거래대금 합 · 마지막 봉의 거래일. */
    record Quote(BigDecimal close, BigDecimal dollarVolume, LocalDate lastDate) {
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

    record Ranked(AlpacaAsset asset, BigDecimal marketCap, BigDecimal dollarVolume) {
    }

    /**
     * 축별 제외 수. <b>합계만 남기지 않는 것이 계약이다</b> — 합계로는 오분류와 정상 배제가
     * 구분되지 않는다({@code docs/phase-criteria.md} P26 축①).
     */
    static final class Exclusions {
        /** 혼성 문구({@link #HYBRID_WORDS})에만 걸려 빠진 티커. 수는 {@code name}에 이미 들어 있다. */
        private final List<String> hybridOnly = new ArrayList<>();
        private int currency;
        private int nonOperating;
        private int noShares;
        private int name;
        private int noClose;
        private int cikDuplicate;
        /** 숨은 상장(ADR-0171) — 받은 것과 갈래별로 뺀 것. 수가 아니라 이름을 남긴다(명부 차이 귀속 재료). */
        private final List<String> hiddenAccepted = new ArrayList<>();
        private final List<String> hiddenStale = new ArrayList<>();
        private final List<String> hiddenUnknown = new ArrayList<>();
        private final List<String> hiddenOffExchange = new ArrayList<>();

        @Override
        public String toString() {
            return ("통화=%d · REIT/SPAC=%d · 주식수없음=%d · 이름=%d(혼성 문구에만 걸림 %s) · 종가없음=%d · CIK중복=%d"
                    + " · 숨은상장 받음=%d").formatted(currency, nonOperating, noShares, name, hybridOnly, noClose,
                    cikDuplicate, hiddenAccepted.size());
        }
    }
}
