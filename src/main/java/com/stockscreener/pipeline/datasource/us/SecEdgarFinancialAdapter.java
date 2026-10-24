package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingTrace;
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
 * 미국 재무를 SEC EDGAR {@code companyfacts}로 받고, 거기 빠진 정기 공시는 그 공시의 XBRL 인스턴스로 보충한다
 * (ADR-0089 · ADR-0173).
 *
 * <p>{@code companyfacts}는 <b>종목당 1콜</b>이다. DART가 (연도·보고서코드)로 4콜을 나눠 받는 것과 달리 전 기간
 * 팩트가 한 응답에 통째로 온다 — 대신 <b>기간·회계연도·정정 여부를 우리가 재구성</b>해야 하고,
 * 그 규칙과 근거는 {@link SecEdgarFacts}에 있다. 보충이 공시 목록(submissions · 페이지)과 빠진 공시마다 색인·인스턴스를
 * 더 받으므로 종목당 콜은 1보다 많다(2026-10-06 RS0: 1,209종목 4,102콜).
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
     * 미국 재적재 관문 설정({@code stockscreener.gate.us} · 기본 꺼짐). 여기서는 관문에서 함께 나갈 어댑터 규칙 변경을
     * {@link SecEdgarFacts#build(tools.jackson.databind.JsonNode, int, boolean)}에 넘기고, 켜졌을 때만 클래스 축 재료를 받는다
     * ({@link ShareClassCollector}). 이 설정을 읽는 곳 전부는 {@code @Value("${stockscreener.gate.us}")}로 센다 — 클래스 축의
     * 유도·판독·명부도 같은 설정 뒤다.
     * 켜는 길(코드 기본값을 뒤집은 jar 하나 — 배포 디렉터리 {@code .env}는 배포마다 저장소 {@code .env}로 덮인다)은 {@code CLAUDE.md} 「재적재·백필」에 있다.
     */
    /** 판마다 예탁 비율의 표지 재료(관문 뒤 · ADR-0183). 시험 생성자는 null이다. */
    private final DepositaryCoverCollector depositary;
    private final boolean usGate;
    /** companyfacts가 빠뜨린 공시를 인스턴스로 채운다(ADR-0173). */
    private final FilingSupplementer supplementer;
    /** 같은 실행 안 재시도 대기 — 보충의 받기와 같은 규칙, 상태(연속 소진 차단)는 이 어댑터 몫이다. */
    private final SecFilingClient.Backoff backoff;
    /** 클래스 축 재료 받기 — 관문 설정이 켜졌을 때만 부른다. 시험 생성자는 {@code null}(받지 않는다). */
    private final ShareClassCollector shareClass;
    /** companyfacts 404를 빈 facts로 받아도 되는가(관문 뒤 · ADR-0190). 시험 생성자는 {@link CompanyFactsHistory#NONE}. */
    private final CompanyFactsHistory history;

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
                                    ShareClassCollector shareClass,
                                    DepositaryCoverCollector depositary,
                                    CompanyFactsHistory history,
                                    @Value("${stockscreener.gate.us}") boolean usGate) {
        this(clientFactory, properties, rateLimiter, cikCache,
                properties.getSecEdgar().getBaseUrl(), usGate, supplement, SecFilingClient.Backoff.standard(), shareClass,
                depositary, history);
    }

    /** MockWebServer 테스트 전용 — base URL만 바꿔 끼운다. 관문 설정은 꺼진 채이고 보충하지 않는다. */
    SecEdgarFinancialAdapter(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             RateLimiter rateLimiter,
                             SecTickerCikCache cikCache,
                             String baseUrl) {
        this(clientFactory, properties, rateLimiter, cikCache, baseUrl, false, FilingSupplementer.NONE);
    }

    /** 시험 전용 — 재시도 대기는 기다리지 않는다(대기 자체는 {@code SecRetryBackoffTest}가 묻는다). */
    SecEdgarFinancialAdapter(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             RateLimiter rateLimiter,
                             SecTickerCikCache cikCache,
                             String baseUrl,
                             boolean usGate,
                             FilingSupplementer supplementer) {
        this(clientFactory, properties, rateLimiter, cikCache, baseUrl, usGate, supplementer, CompanyFactsHistory.NONE);
    }

    /** 시험 전용 — companyfacts 이력 판정을 끼운다(ADR-0190). */
    SecEdgarFinancialAdapter(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             RateLimiter rateLimiter,
                             SecTickerCikCache cikCache,
                             String baseUrl,
                             boolean usGate,
                             FilingSupplementer supplementer,
                             CompanyFactsHistory history) {
        this(clientFactory, properties, rateLimiter, cikCache, baseUrl, usGate, supplementer,
                new SecFilingClient.Backoff(SecFilingClient.Backoff.STANDARD_MILLIS, millis -> { }), null, null, history);
    }

    SecEdgarFinancialAdapter(ExternalApiClientFactory clientFactory,
                             DataSourceProperties properties,
                             RateLimiter rateLimiter,
                             SecTickerCikCache cikCache,
                             String baseUrl,
                             boolean usGate,
                             FilingSupplementer supplementer,
                             SecFilingClient.Backoff backoff,
                             ShareClassCollector shareClass,
                             DepositaryCoverCollector depositary,
                             CompanyFactsHistory history) {
        DataSourceProperties.Source source = properties.getSecEdgar();
        this.client = clientFactory.create(source, baseUrl, SecEdgarHeaders.of(source));
        this.rateLimiter = rateLimiter;
        this.cikCache = cikCache;
        this.userAgentPresent = source.hasApiKey();
        this.usGate = usGate;
        this.supplementer = supplementer;
        this.backoff = backoff;
        this.shareClass = shareClass;
        this.depositary = depositary;
        this.history = history;
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
        // 행에 묶인 등록인이 먼저다(ADR-0180) — 오늘 티커 파일은 교체·재사용 기호에서 다른 등록인을 준다(REAX · XRPN). 묶이지 않은
        // 행(재무 행 0 · 이름 축)만 오늘 파일로 푼다.
        Long bound = security.identity().secCik();
        Optional<Long> cik = bound != null ? Optional.of(bound) : cikCache.findCik(security.ticker());
        if (cik.isEmpty()) {
            // 신선한 매핑 판에 없다 — 소스가 결정적으로 답했다(ADR-0174 · 상장폐지 종목은 그 파일에서 빠진다). 낡은 판의 부재는
            // findCik가 TRANSIENT로 던진다.
            throw DataSourceException.answered(DataSourceException.Kind.NOT_FOUND,
                    SecEdgarResponse.SOURCE_NAME,
                    "SEC 매핑 파일에 없는 티커다: " + security.ticker()
                            + " — ETF·펀드·우선주·워런트·유닛이 여기 든다.");
        }

        String context = "companyfacts %s %s".formatted(
                security.ticker(), SecTickerCikCache.padded(cik.get()));
        ResponseEntity<String> response = factsResponse(cik.get(), context);
        JsonNode facts;
        if (response.getStatusCode().value() == 404 && usGate && history.supplementOnly(security.stockId(), cik.get())) {
            // companyfacts가 그 발행사를 아직 안 지었다 — 공시 인스턴스는 재무를 준다(MAKO · SCZM · TGE 40-F/20-F · 2026-10-24 실측).
            // 「200인데 facts가 빔」(AUGO · AERO)과 같은 갈래로 공시 보충에 넘긴다. companyfacts 이력이 있는 종목의 404는 그대로
            // NOT_FOUND다 — 일시 사건이나 등록인 교체를 빈 트리로 이으면 클래스 축 재료 · 부재 표 · 보충 판이 덮인다(ADR-0190).
            log.info("companyfacts 404 — 빈 트리로 공시 보충에 넘긴다. {}", context);
            facts = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        } else {
            facts = SecEdgarResponse.requireFacts(response.getStatusCode(), response.getBody(), context);
        }
        if (!(facts instanceof ObjectNode tree)) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SecEdgarResponse.SOURCE_NAME,
                    "facts가 객체가 아니다. " + context);
        }

        // 공시 소비의 근거 ①(ADR-0176 2차): companyfacts가 us-gaap를 싣는 접수번호. MUST: 보충 **전**에 잰다 — 보충이 트리에
        // 팩트를 더하므로 뒤에 재면 보충으로 실은 공시가 섞이고, 보충의 성패는 소비가 보충 표로 따로 판정한다.
        // 관문 켬은 IFRS 공시도 companyfacts가 실은 공시다(ADR-0182) — 안 세면 그 공시가 「소비할 근거 없음」으로 남는다.
        TaxonomyFrame.Result frame = usGate ? TaxonomyFrame.of(tree) : null;
        Set<String> carried = SecFilingSupplement.usGaapAccessions(tree);
        if (frame != null) {
            carried = new java.util.HashSet<>(carried);
            carried.addAll(SecFilingSupplement.usGaapAccessions(frame.facts()));
        }

        // 클래스 축 재료(관문 뒤). MUST: 보충 **전**에 — 보충이 트리에 팩트를 더하고, 판정 엔진은 companyfacts 그대로로 검증됐다.
        // 관문 켬의 틀(TaxonomyFrame)로 넘긴다 — us-gaap에서만 공시 목록을 지으면 IFRS 공시가 앵커가 못 되어 표지를 클래스(차원)로만
        // 싣는 발행사(TRI · VALE · BIP)의 주식수를 영영 못 읽고, 섞인 us-gaap 팩트가 앵커를 받은 IFRS 발행사(VALE · WFG)는 그 뒤
        // IFRS 공시가 「미판정 = 숨김」에 걸린다(ADR-0182).
        com.stockscreener.pipeline.datasource.us.shareclass.IssuerFacts classInput =
                usGate && shareClass != null ? shareClass.saveInput(security, cik.get(), frame.shareClassFacts()) : null;

        // companyfacts가 빠뜨린 정기 공시를 그 공시의 인스턴스로 채운다(ADR-0173). 실패는 종목을 세우지 않고 판 보호로 낸다.
        SecFilingSupplement.Outcome supplement = supplementer.supplement(cik.get(), tree, fromFiscalYear, context, usGate);
        if (supplement.fromStore() + supplement.fetched() > 0 || !supplement.failed().isEmpty()
                || supplement.vintagesUncertain()) {
            log.info("공시 보충. ticker={} 받음={} 저장분={} 옛 판 저장분={} 더한 팩트={} 실패={} 목록 모름={}",
                    security.ticker(), supplement.fetched(), supplement.fromStore(), supplement.staleReused(),
                    supplement.merged(), supplement.failed(), supplement.vintagesUncertain());
        }

        // 클래스 축 인스턴스(관문 뒤) — 보충 **뒤**: 보충이 이번에 실은 정기 공시도 앵커 후보다.
        List<DataSourceException.Kind> unfetchedClassFacts = List.of();
        if (classInput != null) {
            ShareClassCollector.Outcome classes = shareClass.fetchInstances(cik.get(), classInput, context);
            if (classes.fetched() > 0 || !classes.failed().isEmpty()) {
                log.info("클래스 축 인스턴스. ticker={} 후보={} 받음={} 실패={}",
                        security.ticker(), classes.targets(), classes.fetched(), classes.failed());
            }
            unfetchedClassFacts = classes.unresolved();
        }
        if (usGate && depositary != null) {
            // 판마다 예탁 비율의 재료(ADR-0183) — 예탁증권 줄 발행사의 2019 뒤 정기 공시 표지. 받지 못한 것은 클래스 축처럼 시장 미완료의
            // 재료로 싣는다(다음 받기가 다시 받는다).
            DepositaryCoverCollector.Outcome covers = depositary.collect(security, cik.get(), context);
            if (covers.fetched() > 0 || !covers.failed().isEmpty()) {
                log.info("예탁 표지. ticker={} 대상={} 받음={} 실패={}", security.ticker(), covers.targets(), covers.fetched(),
                        covers.failed());
            }
            if (!covers.unresolved().isEmpty()) {
                List<DataSourceException.Kind> merged = new java.util.ArrayList<>(unfetchedClassFacts);
                merged.addAll(covers.unresolved());
                unfetchedClassFacts = merged;
            }
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
        // 통화 때문에 안 담은 판은 (라벨, 공시일)로 따로 넘긴다 — 원장은 그 판을 주는데 우리가 안 담았다
        // (SecEdgarFacts.Result.withheld). 안 넘기면 회수가 이미 저장된 비USD 정정판을 지운다(실측 2026-10-19 후보 소비 FR).
        return new FinancialFetch(result.reports(), result.absences(), true,
                supplement.unconfirmedVintages(), supplement.supplementedVintages(), supplement.vintagesUncertain(),
                supplement.unfetched(), FilingTrace.ofAccessions(carried), unfetchedClassFacts,
                new FinancialFetch.Registrant(cik.get(), CompanyFactsAccessions.of(tree)), result.withheld());
    }

    /**
     * companyfacts를 받는다 — 보충의 받기와 같은 자리({@link SecFilingClient#fetch})로: 전송 실패 · 5xx · JSON으로 안 읽히는
     * 2xx는 같은 실행 안에서 대기를 두고 {@link SecFilingClient#TRANSIENT_RETRIES}번 다시 받는다. 한 번에 끝내면 끊긴 전송
     * 하나가 그 종목의 재무를 다음 FR(25일 뒤)까지 비운다 — 원장은 그 파일을 정직하게 주는데 우리 받기가 한 번만 물은 것이다
     * (2026-10-06 반증). 404(매핑 · 미등록) · 403 · 429와 한도는 다시 묻지 않는다.
     *
     * <p>MUST: 본문을 <b>문자열로</b> 받는다. 오류 본문이 JSON이 아니라 XML이다(실측:
     * {@code <Error><Code>NoSuchKey</Code>…}). {@code toEntity(JsonNode.class)}로 받으면 <b>상태코드를 보기도 전에 파싱이
     * 터지고</b>, 그 예외가 전송 실패로 감싸져 404가 {@code TRANSIENT}가 된다 — 재시도해도 영원히 안 되는 것을 재시도 대상으로
     * 기록하게 된다. 실제로 그렇게 났다.
     */
    /** companyfacts 원문 — 신원 판정의 포함 검사가 같은 받기 자리로 받는다(ADR-0180 · 받기를 두 벌 두지 않는다). */
    JsonNode companyFacts(long cik, String context) throws DataSourceException {
        requireUserAgent();
        return fetchFacts(cik, context);
    }

    private JsonNode fetchFacts(long cik, String context) throws DataSourceException {
        ResponseEntity<String> response = factsResponse(cik, context);
        return SecEdgarResponse.requireFacts(response.getStatusCode(), response.getBody(), context);
    }

    private ResponseEntity<String> factsResponse(long cik, String context) throws DataSourceException {
        // MUST: CIK를 10자리 zero-padding한다. 안 하면 404이고, 개발가이드가 그것을
        // 404의 최다 원인으로 적었다. 실측에서도 CIK320193.json이 404였다.
        return SecFilingClient.fetch(rateLimiter, backoff, () -> client.get()
                        .uri("/api/xbrl/companyfacts/{cik}.json", SecTickerCikCache.padded(cik))
                        .retrieve()
                        .toEntity(String.class),
                received -> SecEdgarResponse.unreadableJson(received.getBody()), context);
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
