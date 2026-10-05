package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FinancialFetch;
import com.stockscreener.pipeline.datasource.FinancialDataSource;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
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
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 미국 재무를 SEC EDGAR {@code companyfacts}로 받는다(ADR-0089).
 *
 * <p><b>종목당 1콜</b>이다. DART가 (연도·보고서코드)로 4콜을 나눠 받는 것과 달리 전 기간
 * 팩트가 한 응답에 통째로 온다 — 대신 <b>기간·회계연도·정정 여부를 우리가 재구성</b>해야 하고,
 * 그 규칙과 근거는 {@link SecEdgarFacts}에 있다.
 *
 * <p><b>호스트가 둘이다.</b> 이 어댑터는 {@code data.sec.gov}를 쓰고, 티커→CIK 매핑 파일은
 * {@code www.sec.gov}라 {@link SecTickerCikCache}가 따로 연다. <b>리미터는 공유한다</b> —
 * 초당 한도가 사용자 단위로 두 호스트에 함께 걸린다.
 *
 * <p><b>인증이 없다.</b> {@code User-Agent}만 요구하고 그 값은 시크릿이 아니라 신원 표시다.
 * 그래서 {@code @ConditionalOnApiKey}가 보는 {@code api-key} 자리에 그것이 들어간다
 * ({@link SecEdgarHeaders}가 그 이유를 적는다).
 *
 * <p><b>모르는 티커는 {@code NOT_FOUND}이고 그것이 정상이다.</b> 우리 유니버스 8,504 중
 * 1,815가 매핑 파일에 없다 — 대부분 ETF·펀드와 우선주·워런트·유닛이다. 실패로 기록되지만
 * <b>재무를 낼 수 없는 종목이라는 사실 자체가 그 기록의 값</b>이다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecEdgarFinancialAdapter implements FinancialDataSource {

    private static final Logger log = LoggerFactory.getLogger(SecEdgarFinancialAdapter.class);

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final SecTickerCikCache cikCache;
    private final boolean userAgentPresent;
    /**
     * 미국 재적재 관문 설정({@code stockscreener.gate.us} · 기본 꺼짐). <b>이 설정을 읽는 곳은 여기 하나다</b> —
     * 관문에서 함께 나갈 어댑터 규칙 변경을 {@link SecEdgarFacts#build(tools.jackson.databind.JsonNode, int, boolean)}에 넘긴다.
     * 켜는 길(코드 기본값을 뒤집은 jar 하나 — 배포 디렉터리 {@code .env}는 배포마다 저장소 {@code .env}로 덮인다)은 {@code CLAUDE.md} 「재적재·백필」에 있다.
     */
    private final boolean usGate;
    /** companyfacts가 빠뜨린 공시를 인스턴스로 채운다(ADR-0173). */
    private final FilingSupplementer supplementer;

    /**
     * MUST — {@code @Autowired}를 유지한다. 생성자가 둘이면 Spring이 어느 쪽을 쓸지 정하지
     * 못해 <b>기동에 실패</b>하고, 단위 테스트는 생성자를 직접 부르므로 그것을 잡지 못한다.
     */
    @Autowired
    public SecEdgarFinancialAdapter(ExternalApiClientFactory clientFactory,
                                    DataSourceProperties properties,
                                    @Qualifier("secEdgarRateLimiter") RateLimiter rateLimiter,
                                    SecTickerCikCache cikCache,
                                    SecFilingSupplement supplement,
                                    @Value("${stockscreener.gate.us}") boolean usGate) {
        this(clientFactory, properties, rateLimiter, cikCache,
                properties.getSecEdgar().getBaseUrl(), usGate, supplement);
    }

    /** MockWebServer 테스트 전용 — base URL만 바꿔 끼운다. 관문 설정은 꺼진 채이고 보충하지 않는다. */
    SecEdgarFinancialAdapter(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             RateLimiter rateLimiter,
                             SecTickerCikCache cikCache,
                             String baseUrl) {
        this(clientFactory, properties, rateLimiter, cikCache, baseUrl, false, FilingSupplementer.NONE);
    }

    SecEdgarFinancialAdapter(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             RateLimiter rateLimiter,
                             SecTickerCikCache cikCache,
                             String baseUrl,
                             boolean usGate,
                             FilingSupplementer supplementer) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.client = clientFactory.create(source, baseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.cikCache = cikCache;
        this.userAgentPresent = source.hasApiKey();
        this.usGate = usGate;
        this.supplementer = supplementer;
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of(UsMarket.CODE);
    }

    @Override
    public String sourceName() {
        return SecEdgarResponse.SOURCE_NAME;
    }

    @Override
    public FinancialFetch fetchReports(SecurityRef security, int fromFiscalYear)
            throws DataSourceException {
        requireUserAgent();
        Optional<Long> cik = cikCache.findCik(security.ticker());
        if (cik.isEmpty()) {
            throw new DataSourceException(DataSourceException.Kind.NOT_FOUND,
                    SecEdgarResponse.SOURCE_NAME,
                    "SEC 매핑 파일에 없는 티커다: " + security.ticker()
                            + " — ETF·펀드·우선주·워런트·유닛이 여기 든다.");
        }

        String context = "companyfacts %s %s".formatted(
                security.ticker(), SecTickerCikCache.padded(cik.get()));
        ResponseEntity<String> response = call(cik.get(), context);
        JsonNode facts = SecEdgarResponse.requireFacts(
                response.getStatusCode(), response.getBody(), context);
        if (!(facts instanceof ObjectNode tree)) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "facts가 객체가 아니다. " + context);
        }

        // companyfacts가 빠뜨린 정기 공시를 그 공시의 인스턴스로 채운다(ADR-0173). 실패는 종목을 세우지 않고 판 보호로 낸다.
        SecFilingSupplement.Outcome supplement = supplementer.supplement(cik.get(), tree, fromFiscalYear, context);
        if (supplement.fromStore() + supplement.fetched() > 0 || !supplement.failed().isEmpty()
                || supplement.vintagesUncertain()) {
            log.info("공시 보충. ticker={} 받음={} 저장분={} 옛 판 저장분={} 더한 팩트={} 실패={} 목록 모름={}",
                    security.ticker(), supplement.fetched(), supplement.fromStore(), supplement.staleReused(),
                    supplement.merged(), supplement.failed(), supplement.vintagesUncertain());
        }

        SecEdgarFacts.Result result = SecEdgarFacts.build(tree, fromFiscalYear, usGate);
        if (result.dropped() > 0) {
            // MUST NOT: 조용히 버리지 않는다. 「없다」와 「우리가 못 만들었다」는 다르고,
            // 그 구분이 없으면 결손 판정이 서지 않는다(P22 ①축).
            log.info("기간을 만들지 못한 팩트를 버렸다. ticker={} 버림={}건 만든 보고서={}건",
                    security.ticker(), result.dropped(), result.reports().size());
        }
        // companyfacts + 보충이 판 이력 전부를 싣는다 — 저장 계층이 「여기 없는 공시일은 더 이상 그 라벨의 판이
        // 아니다」로 읽어도 되는 근거다(FinancialFetch 참조). companyfacts 혼자로는 그렇지 않다: 정기 공시 일부의
        // us-gaap를 통째로 안 싣는다(ADR-0173 — 83 CIK 표본 87건). 보충이 못 실은 공시는 그 제출일을 따로 넘겨 지킨다.
        return new FinancialFetch(result.reports(), result.absences(), true,
                supplement.unconfirmedVintages(), supplement.supplementedVintages(), supplement.vintagesUncertain());
    }

    /**
     * MUST: 본문을 <b>문자열로</b> 받는다.
     *
     * <p>오류 본문이 JSON이 아니라 XML이다(실측: {@code <Error><Code>NoSuchKey</Code>…}).
     * {@code toEntity(JsonNode.class)}로 받으면 <b>상태코드를 보기도 전에 파싱이 터지고</b>,
     * 그 예외가 아래 {@code catch}에 걸려 404가 {@code TRANSIENT}가 된다 — 재시도해도
     * 영원히 안 되는 것을 재시도 대상으로 기록하게 된다. 실제로 그렇게 났다.
     */
    private ResponseEntity<String> call(long cik, String context) throws DataSourceException {
        if (!rateLimiter.hasDailyQuota()) {
            throw new DataSourceException(DataSourceException.Kind.RATE_LIMITED,
                    SecEdgarResponse.SOURCE_NAME,
                    "일일 호출 한도를 소진했다(사용 " + rateLimiter.usedToday() + "회). " + context);
        }
        try {
            rateLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "호출 대기 중 인터럽트됐다. " + context, e);
        }
        try {
            // MUST: CIK를 10자리 zero-padding한다. 안 하면 404이고, 개발가이드가 그것을
            // 404의 최다 원인으로 적었다. 실측에서도 CIK320193.json이 404였다.
            return client.get()
                    .uri("/api/xbrl/companyfacts/{cik}.json", SecTickerCikCache.padded(cik))
                    .retrieve()
                    .toEntity(String.class);
        } catch (RuntimeException e) {
            // 벤더 예외가 상위로 새지 않게 여기서 감싼다(ADR-0006).
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    SecEdgarResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }
    }

    /**
     * 호출 <b>전에</b> 신원 표시를 본다.
     *
     * <p>이것이 이 소스에서 실제로 일어나는 유일한 「권한 문제」이고, 그래서 여기서만
     * {@code AUTH}가 난다 — {@link SecEdgarResponse}가 403을 {@code AUTH}로 접지 않는 이유의
     * 반대편이다. 비어 있으면 SEC가 미분류 봇으로 판정해 차단하는데, 그 차단은
     * <b>키가 틀린 것처럼 보이지 않는다.</b>
     */
    private void requireUserAgent() throws DataSourceException {
        if (!userAgentPresent) {
            throw new DataSourceException(DataSourceException.Kind.AUTH,
                    SecEdgarResponse.SOURCE_NAME,
                    "SEC_EDGAR_USER_AGENT가 비어 있다. SEC는 신원을 밝히지 않는 요청을 차단한다.");
        }
    }
}
