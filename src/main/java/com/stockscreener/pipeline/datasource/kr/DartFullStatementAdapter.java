package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FullStatementDataSource;
import com.stockscreener.pipeline.datasource.FilingTrace;
import com.stockscreener.pipeline.datasource.FullStatementResult;
import com.stockscreener.pipeline.datasource.RawFinancialReport;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.StatementPeriod;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DART 단일회사 <b>전체</b> 재무제표 어댑터({@code fnlttSinglAcntAll}).
 *
 * <p>주요계정({@link DartFinancialAdapter})이 주지 않는 것을 받는다. 실측(dart-financial.md의 「계정별 가용성」
 * 절)에서 이 전환으로
 * 실제로 열리는 것은 <b>영업활동현금흐름 하나</b>이고, 그 밖에 자본변동표가 함께 와서 dart-financial.md의
 * 「계정 집합이 회사마다 다르다」 절의
 * 이익잉여금 결손을 여는 길이 된다. 이자비용·감가상각비는 <b>여전히 얻지 못한다</b> —
 * 표본 4사 중 금융지주에만 표준코드로 왔다.
 *
 * <p>주요계정과의 요청 차이는 {@code fs_div}가 <b>필수</b>라는 것뿐이다. 응답 행에는
 * {@code fs_div}가 없으므로 어느 쪽을 받았는지는 요청만 안다. 연결이 비면 별도로 다시 부르고,
 * 그때만 호출이 2배가 된다.
 *
 * <p><b>[2026-09-22] 그 「요청만 안다」를 버리지 않고 {@link Fetched#consolidated()}에 싣는다.</b>
 * 별도재무제표에는 비지배 행이 <b>원리적으로</b> 없으므로, 그 부재를 「비지배가 없다」로 읽으면
 * 게이트가 전원 발화한다 — 실측으로 {@code 450080}은 FY의 CFS가 {@code 013}이라 OFS만 오는데
 * <b>비지배 손익이 41.5%</b>인 회사다.
 *
 * <p>MUST NOT: 분기 보고서의 영업활동현금흐름을 저장하지 않는다. 개발가이드가 [3개월] 금액
 * 이라고 밝힌 것은 <b>(포괄)손익계산서뿐</b>이고 현금흐름표는 누적인지 3개월인지 실측되지
 * 않았다(dart-financial.md의 「전체 재무제표」 절). 연간만 쓰는 동안은 드러나지 않지만, 모른 채로 저장해 두면
 * 분기 OCF를 쓰는
 * 조건이 붙는 순간 <b>4배나 1/4로 조용히 틀린다.</b>
 */
// 키가 없으면 등록하지 않는다. 같은 패키지의 다른 DART 어댑터와 같은 이유다.
@ConditionalOnApiKey("dart")
@Component
public class DartFullStatementAdapter implements FullStatementDataSource {

    /** 요청 경로 — 일일 총량을 이 경로로 센다(ADR-0176). */
    private static final String PATH = "/fnlttSinglAcntAll.json";

    private static final Logger log = LoggerFactory.getLogger(DartFullStatementAdapter.class);

    /** 우리 회계기간 → DART 보고서 코드. Q4는 보고서가 없어 여기 없다. */
    private static final Map<StatementPeriod, String> REPORT_CODES =
            new EnumMap<StatementPeriod, String>(Map.of(
                    StatementPeriod.Q1, "11013",
                    StatementPeriod.Q2, "11012",
                    StatementPeriod.Q3, "11014",
                    StatementPeriod.FY, "11011"));

    private static final String CONSOLIDATED = "CFS";
    private static final String SEPARATE = "OFS";

    private final RestClient client;
    private final RateLimiter rateLimiter;
    private final DartDailyQuota dailyQuota;
    private final DartCorpCodeCache corpCodes;
    private final String apiKey;
    /**
     * 저장 이른 판의 인스턴스 — 있으면 그 판의 전체 재무제표를 인스턴스로 다시 지어 함께 낸다({@link DartXbrlStatementRows}).
     * {@code null}이면 이 축이 없던 때와 같다.
     */
    private final DartEarlierInstances earlierInstances;

    public DartFullStatementAdapter(ExternalApiClientFactory clientFactory,
                                    DataSourceProperties properties,
                                    RateLimiter rateLimiter,
                                    DartDailyQuota dailyQuota,
                                    DartCorpCodeCache corpCodes) {
        this(clientFactory, properties, rateLimiter, dailyQuota, corpCodes, (DartEarlierInstances) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DartFullStatementAdapter(ExternalApiClientFactory clientFactory,
                                    DataSourceProperties properties,
                                    @Qualifier("dartRateLimiter") RateLimiter rateLimiter,
                                    DartDailyQuota dailyQuota,
                                    DartCorpCodeCache corpCodes,
                                    org.springframework.beans.factory.ObjectProvider<DartEarlierInstances> earlierInstances) {
        this(clientFactory, properties, rateLimiter, dailyQuota, corpCodes,
                earlierInstances == null ? null : earlierInstances.getIfAvailable());
    }

    /** 국내 관문({@code stockscreener.gate.kr-provisional}) — 켜면 비지배 증거를 값이 0이 아닌 줄로 좁힌다(K9). 시험은 생성자만 부른다. */
    private boolean krGate;

    @org.springframework.beans.factory.annotation.Autowired
    public void krGate(@org.springframework.beans.factory.annotation.Value("${stockscreener.gate.kr-provisional}") boolean on) {
        this.krGate = on;
    }

    DartFullStatementAdapter(ExternalApiClientFactory clientFactory, DataSourceProperties properties, RateLimiter rateLimiter,
                             DartDailyQuota dailyQuota, DartCorpCodeCache corpCodes,
                             DartEarlierInstances earlierInstances) {
        this.earlierInstances = earlierInstances;
        DataSourceProperties.Source source = properties.getDart();
        this.client = clientFactory.create(source);
        this.rateLimiter = rateLimiter;
        this.dailyQuota = dailyQuota;
        this.corpCodes = corpCodes;
        this.apiKey = source.getApiKey();
    }

    @Override
    public Set<String> supportedMarketCodes() {
        return Set.of("KOSPI", "KOSDAQ");
    }

    @Override
    public String sourceName() {
        return DartResponse.SOURCE_NAME;
    }

    @Override
    public FullStatementResult fetchStatements(SecurityRef security, int fromFiscalYear,
                                               int toFiscalYear, Set<StatementPeriod> periods)
            throws DataSourceException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new DataSourceException(DataSourceException.Kind.AUTH, DartResponse.SOURCE_NAME,
                    "DART_API_KEY가 설정되지 않았다.");
        }
        if (periods == null || periods.isEmpty()) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                    "받을 회계기간이 지정되지 않았다. ticker=" + security.ticker());
        }
        if (toFiscalYear < fromFiscalYear) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, DartResponse.SOURCE_NAME,
                    "회계연도 범위가 뒤집혔다: " + fromFiscalYear + "~" + toFiscalYear);
        }
        String corpCode = corpCodes.findCorpCode(security.ticker())
                // 신선한 매핑 판에 없다 — 소스가 결정적으로 답했다(ADR-0174 · 낡은 판의 부재는 캐시가 TRANSIENT로 던진다).
                .orElseThrow(() -> DataSourceException.answered(DataSourceException.Kind.NOT_FOUND,
                        DartResponse.SOURCE_NAME,
                        "DART에 등록되지 않은 종목코드다: " + security.ticker()));

        // ── 1패스: 페치. **보고서를 아직 만들지 않는다.**
        //
        // MUST: 종목 단위 판정을 만들려면 그 종목의 응답이 **한자리에 다 있어야** 한다.
        // 「비지배를 이 기간 뒤로 안 냈는가」는 한 보고서로 답할 수 없고, 행 단위로 답하면
        // 어느 분기에 그 행이 없다는 것만으로 연결 순이익에 지배주주 이름표가 붙는다
        // (DartFullAccountMapper.controllingEquity javadoc이 자본 축에서 같은 자리를 막는다).
        List<Pending> pending = new ArrayList<>();
        int calls = 0;
        int missing = 0;

        for (int year = fromFiscalYear; year <= toFiscalYear; year++) {
            // 선언 순서(FY, Q1..Q3)가 아니라 enum 순서로 돈다. 로그를 읽을 때 기간이 늘 같은
            // 순서로 나와야 종목 간 비교가 된다.
            for (StatementPeriod period : StatementPeriod.values()) {
                if (!periods.contains(period)) {
                    continue;
                }
                Fetched fetched = fetchOne(corpCode, year, period, security);
                calls += fetched.calls();
                if (fetched.list() == null) {
                    missing++;
                    continue;
                }
                pending.add(new Pending(year, period, fetched.list(), fetched.consolidated()));
            }
        }

        // 공시 소비의 근거(ADR-0176 2차) — 보고서마다 응답의 접수번호. 출력과 무관하다.
        Map<FilingTrace.ReportKey, String> seen = FilingTrace.newReportMap();
        for (Pending p : pending) {
            for (JsonNode row : p.list()) {
                FilingTrace.putLatest(seen, new FilingTrace.ReportKey(p.year(), REPORT_CODES.get(p.period())),
                        row.path("rcept_no").asString(""));
            }
        }

        // ── 2패스: 종목 단위 판정(순수). 외부 호출 0.
        LocalDate lastNoncontrolling = lastNoncontrollingIncome(pending, krGate);
        // MUST: 게이트가 몇 판에서 열렸는지 남긴다. 스윕이 이 축을 못 태우므로(픽스처가
        // 종목당 한 기간뿐이다) **적재 직후 검침이 유일한 확인 수단**이고, 로그가 없으면
        // 「게이트가 돌았는가」를 저장된 값으로만 추정하게 된다 — 그 값은 폴백이 쓰던 것과
        // 같아서 구분되지 않는다.
        long gated = pending.stream().filter(p -> gateOpen(p, lastNoncontrolling)).count();
        long separate = pending.stream().filter(p -> !p.consolidated()).count();
        if (gated > 0 || separate > 0) {
            log.info("지배주주 손익 게이트. ticker={} 판={} 게이트열림={} 별도재무제표={} 마지막비지배={}",
                    security.ticker(), pending.size(), gated, separate, lastNoncontrolling);
        }

        // ── 3패스: 보고서 생성.
        List<RawFinancialReport> reports = new ArrayList<>();
        Map<Integer, Map<StatementPeriod, Pending>> pendingByYear = new java.util.HashMap<>();
        Map<Integer, Map<String, RawFinancialReport>> reportsByYear = new java.util.HashMap<>();
        for (int year = fromFiscalYear; year <= toFiscalYear; year++) {
            JsonNode annual = null;
            JsonNode third = null;
            Map<StatementPeriod, Pending> yearPending = new java.util.EnumMap<>(StatementPeriod.class);
            Map<String, RawFinancialReport> yearReports = new java.util.LinkedHashMap<>();
            pendingByYear.put(year, yearPending);
            reportsByYear.put(year, yearReports);
            boolean annualConsolidated = false;
            boolean thirdConsolidated = false;
            // Q4의 지배주주 순이익을 유도하려면 그 해 FY와 Q1~Q3가 한자리에 있어야 한다.
            List<RawFinancialReport> thisYear = new ArrayList<>();
            for (Pending p : pending) {
                if (p.year() != year) {
                    continue;
                }
                RawFinancialReport report = toReport(p.list(), year, p.period(), security,
                        gateOpen(p, lastNoncontrolling), p.consolidated());
                reports.add(report);
                thisYear.add(report);
                yearPending.put(p.period(), p);
                yearReports.put(p.period().label(), report);
                if (p.period() == StatementPeriod.FY) {
                    annual = p.list();
                    annualConsolidated = p.consolidated();
                }
                if (p.period() == StatementPeriod.Q3) {
                    third = p.list();
                    thirdConsolidated = p.consolidated();
                }
            }
            if (annual != null && periods.contains(StatementPeriod.Q3)) {
                RawFinancialReport q4 = toFourthQuarterBalance(annual, year, security, thisYear);
                RawFinancialReport fourth = withFourthQuarterDiscontinued(q4, annual, annualConsolidated, third, thirdConsolidated, krGate);
                reports.add(fourth);
                yearReports.put("Q4", fourth);
            }
        }
        if (earlierInstances != null) {
            // 판마다 그 판의 원장으로 — DART가 다시 안 주는 저장 이른 판의 전체 재무제표를 그 판의 인스턴스로 짓는다. 맨 뒤에 둔다: 쓰기기가
            // 라벨의 최신 판을 먼저 만나야 이른 판이 「그 공시일의 행 채우기」로 간다(그 라벨의 첫 행으로 들어가지 않게).
            for (Pending p : pending) {
                reports.addAll(earlierVintageReports(p, security, lastNoncontrolling));
            }
        }
        List<com.stockscreener.pipeline.datasource.FlowBasis> bases = new ArrayList<>();
        for (int year = fromFiscalYear; year <= toFiscalYear; year++) {
            bases.addAll(flowBases(year, pendingByYear.get(year), pendingByYear.getOrDefault(year + 1, Map.of()),
                    pendingByYear.getOrDefault(year + 2, Map.of()), reportsByYear.get(year), krGate));
        }
        return new FullStatementResult(reports, calls, missing, FilingTrace.ofReports(seen), bases);
    }

    private static boolean discontinuedId(String id) {
        return DartFullAccountMapper.ID_DISCONTINUED.contains(id) || DartFullAccountMapper.ID_DISCONTINUED_CONTROLLING.contains(id)
                || DartFullAccountMapper.ID_DISCONTINUED_NONCONTROLLING.contains(id);
    }

    /** 그 보고서의 저장 이른 판마다 인스턴스로 지은 보고서. 인스턴스가 그 기간의 표를 못 주면 그 판은 뺀다. */
    private List<RawFinancialReport> earlierVintageReports(Pending latest, SecurityRef security, LocalDate lastNoncontrolling)
            throws DataSourceException {
        String reprtCode = REPORT_CODES.get(latest.period());
        String latestRcept = latest.list().get(0).path("rcept_no").asString("");
        List<RawFinancialReport> out = new ArrayList<>();
        for (DartEarlierInstances.Instance instance : earlierInstances.of(security.stockId(), latest.year(), reprtCode, latestRcept)) {
            com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Statements facts;
            try (java.io.InputStream in = new java.io.ByteArrayInputStream(instance.xbrl())) {
                facts = com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.readStatements(in,
                        security.ticker() + " " + instance.rceptNo());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("바이트 배열 스트림이 닫히다 실패했다", e);
            }
            Map<String, String> labels = instance.labelsKo() == null ? Map.of()
                    : com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlLabels.entityLabels(instance.labelsKo(),
                            security.ticker() + " " + instance.rceptNo());
            DartXbrlStatementRows.Built built = DartXbrlStatementRows.rows(facts.facts(), latest.year(), latest.period(),
                    instance.rceptNo(), reprtCode, DartXbrlStatementRows.Face.of(latest.list()), labels);
            if (built == null) {
                log.info("이른 판 인스턴스가 그 기간의 표를 안 준다 — 그 판은 짓지 않는다. ticker={} {}/{} 접수번호={}",
                        security.ticker(), latest.year(), latest.period().label(), instance.rceptNo());
                continue;
            }
            Pending p = new Pending(latest.year(), latest.period(), built.list(), built.consolidated());
            out.add(instanceReport(built, latest.year(), latest.period(), security, gateOpen(p, lastNoncontrolling)));
        }
        return out;
    }

    /** 인스턴스로 옮긴 행 하나로 보고서를 짓는다 — 응답과 같은 매퍼 · 다른 것은 중단영업 부재의 뜻 하나다. */
    RawFinancialReport instanceReport(DartXbrlStatementRows.Built built, int year, StatementPeriod period, SecurityRef security,
                                      boolean gateOpen) {
        // 부재는 판정하지 않는다 — 인스턴스는 표준계정코드 없는 줄을 못 옮겨 「그 판이 안 실었다」를 모른다(저장값을 지우지 않게).
        RawFinancialReport report = toReport(built.list(), year, period, security, gateOpen, built.consolidated())
                .withoutAbsenceJudgement();
        if (built.unknownIncome().stream().anyMatch(DartFullStatementAdapter::discontinuedId)) {
            // 인스턴스에 중단영업 사실이 있는데 행으로 못 옮겼다(누계로만 · 본문 줄 없음 · 값 갈림) — 3개월 값은 0이 아니라 모른다(매퍼의 「줄이
            // 없으면 0」은 응답에 줄이 없을 때의 뜻이다).
            // 비워 두면 쓰기기가 저장값을 안 지운다.
            report = report.withoutDiscontinued();
        }
        return report;
    }

    /**
     * <b>이 종목이 손익 비지배를 마지막으로 낸 기간의 종료일.</b> 없으면 {@code null}.
     *
     * <p><b>MUST: 연결재무제표(CFS) 응답만 증거로 센다.</b> 별도재무제표에는 지분 구분이
     * <b>원리적으로</b> 없으므로 그 부재는 「비지배가 없다」가 아니라 「물어본 적이 없다」이다.
     * 실측(2026-09-22 · 라이브 6콜)으로 {@code 450080}·{@code 195940}·{@code 323410}은
     * FY의 CFS가 {@code 013}이고 OFS만 162~179행을 준다. 그런데 {@code 450080}은
     * <b>비지배 자본 21.94% · 비지배 손익 41.5%</b>인 회사다 — OFS를 증거로 세면
     * 게이트가 켜져 <b>별도 순이익이 지배주주 값으로 박힌다.</b>
     *
     * <p><b>값이 아니라 행의 존재</b>를 본다. 비지배가 정확히 0인 회사도 행은 내고,
     * 그것이 「비지배를 낸 적 있다」의 증거다.
     */
    static LocalDate lastNoncontrollingIncome(List<Pending> pending) {
        return lastNoncontrollingIncome(pending, false);
    }

    /** @param nonzeroOnly 국내 관문 켬(계획 K9) — 증거를 값이 0이 아닌 비지배 줄 · 재무상태표 비지배지분으로 좁힌다({@link DartFullAccountMapper#hasNonzeroNoncontrolling}) */
    static LocalDate lastNoncontrollingIncome(List<Pending> pending, boolean nonzeroOnly) {
        LocalDate last = null;
        for (Pending p : pending) {
            if (!p.consolidated()) {
                continue;
            }
            List<JsonNode> income = DartFullAccountMapper.sections(p.list(),
                    DartFullAccountMapper.SJ_INCOME,
                    DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
            // 꺼짐: 비지배 귀속 줄이면 무엇이든 증거로 센다(DartFullAccountMapper.hasAnyNoncontrollingIncome).
            boolean evidence = nonzeroOnly
                    ? DartFullAccountMapper.hasNonzeroNoncontrolling(income,
                            DartFullAccountMapper.sections(p.list(), DartFullAccountMapper.SJ_BALANCE))
                    : DartFullAccountMapper.hasAnyNoncontrollingIncome(income);
            if (!evidence) {
                continue;
            }
            LocalDate end = DartReportPeriods.periodRange(p.year(), p.period().label())[1];
            if (last == null || end.isAfter(last)) {
                last = end;
            }
        }
        return last;
    }

    /**
     * <b>이 보고서에서 「비지배 전무」 게이트를 켜도 되는가.</b>
     *
     * <p>둘을 함께 요구한다 — ① <b>이 응답이 CFS</b>여야 하고(OFS는 증거가 아니다)
     * ② <b>이 기간 뒤로 비지배를 안 냈어야</b> 한다. 뒤의 것이 미국
     * {@code SecEdgarFacts.noNoncontrollingIncomeBy}와 같은 술어다 —
     * 「한 번도 안 냈는가」로 물으면 <b>옛 이력에 발목이 잡혀</b> 자회사를 판 회사가 영영 안 열린다.
     *
     * <p><b>같은 회계연도 안에서는 갈래가 안 섞인다.</b> FY가 비지배를 냈으면
     * {@code lastNoncontrolling}이 그 해 종료일이고 Q1~Q3의 시작일은 그보다 이르므로
     * {@code isBefore}가 거짓이 되어 <b>그 해 전체가 게이트 OFF</b>다. 그래서
     * {@code deriveFourthQuarterControlling}의 뺄셈에 「직접 태그 − 게이트 값」이 섞이지 않는다.
     */
    static boolean gateOpen(Pending p, LocalDate lastNoncontrolling) {
        if (!p.consolidated()) {
            return false;
        }
        LocalDate start = DartReportPeriods.periodRange(p.year(), p.period().label())[0];
        return lastNoncontrolling == null || lastNoncontrolling.isBefore(start);
    }

    /**
     * 한 (연도, 기간)의 전체 재무제표를 받는다. 연결이 비면 별도로 다시 부른다.
     *
     * <p>{@code status 013}(데이터 없음)을 예외로 올리지 않는 이유는 주요계정 어댑터와 같다 —
     * 아직 공시되지 않은 분기와 <b>상장 전 회계연도</b>가 정상이기 때문이다. 이 phase에서는
     * 후자가 특히 중요하다: {@code 013}이 곧 "그 해의 사업보고서가 존재하지 않는다"의 답이고,
     * 그것으로 <b>적재로 메울 수 있는 결손과 원리적으로 없는 결손이 갈린다.</b>
     */
    private Fetched fetchOne(String corpCode, int year, StatementPeriod period, SecurityRef security)
            throws DataSourceException {
        JsonNode consolidated = call(corpCode, year, period, CONSOLIDATED, security);
        if (consolidated != null && !consolidated.isEmpty()) {
            return new Fetched(consolidated, 1, true);
        }
        JsonNode separate = call(corpCode, year, period, SEPARATE, security);
        if (separate != null && !separate.isEmpty()) {
            return new Fetched(separate, 2, false);
        }
        return new Fetched(null, 2, false);
    }

    private JsonNode call(String corpCode, int year, StatementPeriod period, String fsDiv,
                          SecurityRef security) throws DataSourceException {
        String reportCode = REPORT_CODES.get(period);
        String context = "ticker=" + security.ticker() + " " + year + "/" + period
                + " reprt=" + reportCode + " fs_div=" + fsDiv;

        // MUST: 이 가드를 지우지 않는다. 리미터의 acquire()는 분당 간격만 대기하고 일일 한도는
        // 보지 않는다 — 여기가 없으면 한도를 조용히 넘는다. 어댑터마다 같은 블록이 있는 이유다.
        DartResponse.acquire(rateLimiter, dailyQuota, PATH, context);

        ResponseEntity<byte[]> response;
        try {
            response = client.get().uri(uri -> uri
                            .path(PATH)
                            .queryParam("crtfc_key", apiKey)
                            .queryParam("corp_code", corpCode)
                            .queryParam("bsns_year", year)
                            .queryParam("reprt_code", reportCode)
                            .queryParam("fs_div", fsDiv)
                            .build())
                    .retrieve()
                    // 본문을 바이트로 받는다 — 상태를 먼저 보고 UTF-8로 판다(DartResponse.read · 문자열로 받으면 한글이 깨진다).
                    .toEntity(byte[].class);
        } catch (RuntimeException e) {
            throw new DataSourceException(DataSourceException.Kind.TRANSIENT,
                    DartResponse.SOURCE_NAME, "호출에 실패했다. " + context, e);
        }

        try {
            return DartResponse.requireList(DartResponse.read(response, context), context);
        } catch (DataSourceException e) {
            if (DartResponse.isNoData(e)) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 응답 행을 포트 DTO로 옮긴다.
     *
     * <p>손익을 {@code IS}와 {@code CIS} 양쪽에서 찾되 <b>{@code IS}를 먼저 본다.</b> 표본에서
     * 삼성전자만 둘 다 갖고 나머지 셋은 {@code CIS}뿐이었다. 둘 다 있을 때 매출·영업이익은
     * {@code IS}에 있으므로 순서가 뜻을 정한다.
     */
    RawFinancialReport toReport(JsonNode list, int year, StatementPeriod period,
                                        SecurityRef security, boolean gateOpen,
                                        boolean consolidated) {
        List<JsonNode> balance = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_BALANCE);
        List<JsonNode> income = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_INCOME, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
        List<JsonNode> cashFlow = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_CASH_FLOW);

        JsonNode any = list.get(0);
        String label = period.label();
        LocalDate[] range = DartReportPeriods.periodRange(year, label);

        BigDecimal retainedEarnings = DartFullAccountMapper.find(
                balance, DartFullAccountMapper.ID_RETAINED_EARNINGS, DartAccountMapper.RETAINED_EARNINGS);
        if (retainedEarnings == null) {
            // dart-financial.md의 「계정 집합이 회사마다 다르다」 절의 세 종목이 여기 걸린다. 재무상태표에 행 자체가 없으므로 자본변동표의
            // 기말 잔액으로 대신한다 — 규칙의 근거는 그 메서드에 있다.
            retainedEarnings = DartFullAccountMapper.retainedEarningsFromEquityChanges(
                    list, security.ticker(), year, label);
        }

        // 게이트(연결 총액 = 지배)는 마지막 갈래다 — 원장이 지배 몫을 다른 줄로 확인해 주면 그것이 먼저다.
        BigDecimal controllingIncome = DartFullAccountMapper.controllingIncome(income, false);
        if (controllingIncome == null) {
            // F2b: 지배주주 순이익 줄 없이 계속 · 중단 지배 몫만 준 보고서(솔브레인홀딩스 FY2025 계속 42.1B + 중단 445.2B).
            controllingIncome = DartFullAccountMapper.controllingIncomeFromParts(income);
        }
        if (controllingIncome == null && consolidated) {
            // 지배 · 비지배 몫을 표준계정코드 없이 실은 연결 보고서 — 항등식(지배 + 비지배 = 당기순이익)이 확인할 때만.
            controllingIncome = DartFullAccountMapper.controllingIncomeFromNonstandardRows(
                    income, DartFullAccountMapper.consolidatedIncome(income), "thstrm_amount");
        }
        if (controllingIncome == null && gateOpen) {
            controllingIncome = DartFullAccountMapper.consolidatedIncome(income);
        }
        // 국내 관문 켬(K19) — 표준 지배 줄을 손익 산수와 자본변동표 지배 열이 함께 반박하면 자본변동표가 확인한 줄로(삼성전기 · LG이노텍).
        BigDecimal confirmed = krGate && consolidated ? DartFullAccountMapper.confirmedControlling(income,
                DartFullAccountMapper.sections(list, DartFullAccountMapper.SJ_EQUITY_CHANGES), "thstrm_amount", cumulativeField(period)) : null;
        if (confirmed != null) {
            controllingIncome = confirmed;
        }

        RawFinancialReport report = new RawFinancialReport(
                year, label, range[0], range[1],
                DartReportPeriods.publishedAt(any, year),
                any.path("reprt_code").asString(null),
                any.path("currency").asString("KRW"),
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_REVENUE, DartAccountMapper.REVENUE),
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_OPERATING_INCOME,
                        DartAccountMapper.OPERATING_INCOME),
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_NET_INCOME, DartAccountMapper.NET_INCOME),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_ASSETS, DartAccountMapper.TOTAL_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY),
                DartFullAccountMapper.controllingEquity(balance),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_LIABILITIES,
                        DartAccountMapper.TOTAL_LIABILITIES),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_ASSETS,
                        DartAccountMapper.CURRENT_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_LIABILITIES,
                        DartAccountMapper.CURRENT_LIABILITIES),
                retainedEarnings,
                operatingCashFlow(cashFlow, period),
                // 발행주식수·EPS·BPS는 이 엔드포인트의 대상이 아니다. 주요계정 어댑터와 같이
                // 비워 두며, 채우려면 stockTotqySttus가 따로 필요하다.
                null, null, null,
                // 계정명 폴백을 두지 않는다. 매출원가는 회사마다 이름이 다양해 폴백이
                // 「매출원가성 비용」류를 집을 위험이 표준코드 미사용분(2~9%)을 메우는
                // 이득보다 크다.
                DartFullAccountMapper.find(
                        income, DartFullAccountMapper.ID_COST_OF_SALES, null),
                // 재무상태표에서만 읽는다. 현금흐름표의 같은 이름은 기초·기말 잔액이다.
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CASH_AND_EQUIVALENTS, null),
                capex(cashFlow, period),
                // 재무상태표에서만 읽는다 — 현금흐름표의 같은 낱말은 증감분이고 손익의 것은
                // 평가손실이다(dart-financial.md의 「계정별 가용성」 절).
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_INVENTORIES, null),
                // 지배기업 소유주 귀속 순이익. 계정명 폴백을 두지 않는 이유가 특히 강하다 —
                // 한 계정을 회사마다 일곱 가지 이름으로 부르고, 새면 연결 총액이 그 자리에
                // 앉아 값이 그럴듯하게 틀린다(ID_NET_INCOME_CONTROLLING javadoc).
                //
                // [2026-09-22] **직접 태그만 보던 것을 네 갈래로 넓힌다.** 같은 응답에서
                // total_equity_controlling은 「자본총계 − 비지배지분」으로 유도하면서 이쪽만
                // 직접 태그에 머물러 있었고, 그 비대칭이 teqc는 있고 nic는 없는 칸을 만들었다.
                controllingIncome,
                null, null, null, null, null, null, null, null,
                // 세율 재료 둘. 유량이라 연간에만 담는다 — 아래 가드 참조.
                incomeTaxExpense(income, period),
                profitBeforeTax(income, period),
                // 소스가 준 행이다.
                false,
                // MUST: **판정일 때만** 담는다. 담으면 FinancialWriter가 저장의 옛 값을 지우므로,
                // 「물어본 적이 없다」(OFS)와 「그 판이 손익을 안 실었다」를 담으면 멀쩡한 값이
                // 날아간다. 담는 것은 「CFS로 물었고 손익은 왔는데, 이 종목이 이 기간 뒤로
                // 비지배를 내서 지배주주 몫을 만들 수 없다」 하나다.
                assertedAbsence(controllingIncome, income, gateOpen, consolidated));
        if (income.isEmpty()) {
            // 손익 행이 안 온 보고서는 중단영업을 「읽지 않았다」(규칙 판 없음) — 줄이 없다고 0을 달면 그 판정이 틀린다.
            return report;
        }
        BigDecimal[] discontinued = DartFullAccountMapper.discontinued(income, false, consolidated, confirmed);
        return report.withDiscontinued(discontinued[0], discontinued[1],
                discontinuedAbsence(report.assertedAbsent(), discontinued[0], discontinued[1]));
    }

    /** 그 보고서의 누계 칸 — 반기 · 3분기 보고서의 당기 칸은 3개월이다. */
    private static String cumulativeField(StatementPeriod period) {
        String label = period.label();
        return "Q2".equals(label) || "Q3".equals(label) ? "thstrm_add_amount" : "thstrm_amount";
    }

    /**
     * <b>4분기 중단영업 = FY − Q3 보고서 누계</b>(F2b). {@code FY − (Q1 + Q2 + Q3)}로 짓지 않는다 — 해 중간에 중단으로
     * 분류되면 앞 분기 원보고서에는 줄이 없는데 FY 중단액에는 그 몫이 들어 있다(솔브레인홀딩스 2025: 445.2B 대 참값 424.4B).
     * Q3 보고서에 중단 줄이 없는데 FY ≠ 0이면(Q3 뒤 재분류 — 카카오 · CJ제일제당 꼴) 그 판에서는 원장이 안 쪼개 준다 — 모른다.
     */
    static RawFinancialReport withFourthQuarterDiscontinued(RawFinancialReport q4, JsonNode annual, boolean annualConsolidated,
                                                         JsonNode third, boolean thirdConsolidated) {
        return withFourthQuarterDiscontinued(q4, annual, annualConsolidated, third, thirdConsolidated, false);
    }

    /** @param krGate 국내 관문 켬 — 두 보고서의 지배주주 순이익을 {@link DartFullAccountMapper#confirmedControlling}과 같은 규칙으로 읽는다 */
    static RawFinancialReport withFourthQuarterDiscontinued(RawFinancialReport q4, JsonNode annual, boolean annualConsolidated,
                                                         JsonNode third, boolean thirdConsolidated, boolean krGate) {
        List<JsonNode> annualIncome = DartFullAccountMapper.sections(annual,
                DartFullAccountMapper.SJ_INCOME, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
        BigDecimal annualControlling = krGate && annualConsolidated ? DartFullAccountMapper.confirmedControlling(annualIncome,
                DartFullAccountMapper.sections(annual, DartFullAccountMapper.SJ_EQUITY_CHANGES), "thstrm_amount", "thstrm_amount") : null;
        BigDecimal[] fy = DartFullAccountMapper.discontinued(annualIncome, false, annualConsolidated, annualControlling);
        BigDecimal consolidated = null;
        BigDecimal controlling = null;
        if (fy[0] != null && fy[0].signum() == 0) {
            consolidated = BigDecimal.ZERO;
            controlling = BigDecimal.ZERO;
        } else if (third != null) {
            List<JsonNode> income = DartFullAccountMapper.sections(third,
                    DartFullAccountMapper.SJ_INCOME, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
            if (DartFullAccountMapper.hasDiscontinuedLine(income)) {
                BigDecimal nineControlling = krGate && thirdConsolidated ? DartFullAccountMapper.confirmedControlling(income,
                        DartFullAccountMapper.sections(third, DartFullAccountMapper.SJ_EQUITY_CHANGES), "thstrm_add_amount",
                        "thstrm_add_amount") : null;
                BigDecimal[] nine = DartFullAccountMapper.discontinued(income, true, thirdConsolidated, nineControlling);
                consolidated = fy[0] != null && nine[0] != null ? fy[0].subtract(nine[0]) : null;
                controlling = fy[1] != null && nine[1] != null ? fy[1].subtract(nine[1]) : null;
            }
        }
        return q4.withDiscontinued(consolidated, controlling,
                discontinuedAbsence(q4.assertedAbsent(), consolidated, controlling));
    }

    /**
     * 전체 재무제표 어댑터가 싣는 손익 칸(재고 A6 · ADR-0200) — DB 컬럼명 → 표준계정 ID. 매출 · 영업이익 · 순이익은 주요계정 어댑터가 싣는다
     * (칸마다 주인 하나). 지배주주 순이익은 표준 줄이 있으면 그 값으로, 없으면 이 소스의 순이익 판정을 따른다.
     */
    private static final Map<String, java.util.Set<String>> FLOW_COLUMNS = new java.util.LinkedHashMap<>();

    static {
        FLOW_COLUMNS.put("cost_of_sales", DartFullAccountMapper.ID_COST_OF_SALES);
        FLOW_COLUMNS.put("net_income_controlling", DartFullAccountMapper.ID_NET_INCOME_CONTROLLING);
        FLOW_COLUMNS.put("discontinued_income", DartFullAccountMapper.ID_DISCONTINUED);
        FLOW_COLUMNS.put("discontinued_income_controlling", DartFullAccountMapper.ID_DISCONTINUED_CONTROLLING);
        FLOW_COLUMNS.put("net_income", DartFullAccountMapper.ID_NET_INCOME);
    }

    /**
     * FY에만 싣는 유량(영업현금흐름 · capex · 법인세 · 세전이익) — 분기 칸이 없어 전기 FY 열(다음 해 사업보고서)로만 잰다(반증 안 렌즈 · 11-01:
     * 연결 전환 · 사업 매각을 건너는 5년 영업현금흐름 합 · 세후 ROIC가 섞였다). 표준계정 ID만 — 이름 폴백 행은 안 잰다.
     */
    private static final Map<String, java.util.Set<String>> ANNUAL_ONLY = Map.of(
            "operating_cash_flow", DartFullAccountMapper.ID_OPERATING_CASH_FLOW,
            "capex", DartFullAccountMapper.ID_CAPEX,
            "income_tax_expense", DartFullAccountMapper.ID_INCOME_TAX_EXPENSE,
            "profit_before_tax", DartFullAccountMapper.ID_PROFIT_BEFORE_TAX);

    /** 중단영업 칸 — 손익 행이 온 보고서에 줄이 없으면 0으로 읽는다(F2b와 같은 판정 · 중단영업은 대개 0에서 생긴다). */
    private static final java.util.Set<String> ZERO_WHEN_ABSENT = java.util.Set.of("discontinued_income", "discontinued_income_controlling");

    /**
     * 재고 A6: 그 해 손익의 기저 판정 — 주요계정 어댑터와 같은 규칙({@link DartFlowBasis}). 연결 · 별도 기저가 갈린 보고서끼리도
     * 잰다 — 다른 기저로 다시 실은 것도 같은 원칙이다(연결 전환).
     */
    static List<com.stockscreener.pipeline.datasource.FlowBasis> flowBases(int year, Map<StatementPeriod, Pending> pending,
                                                                           Map<StatementPeriod, Pending> next,
                                                                           Map<StatementPeriod, Pending> twoLater,
                                                                           Map<String, RawFinancialReport> reports) {
        return flowBases(year, pending, next, twoLater, reports, false);
    }

    /**
     * @param krGate 국내 관문 켬 — 지배주주 순이익은 매퍼({@link DartFullAccountMapper#confirmedControlling})와 같은 규칙으로 판마다 · 칸마다
     *               읽는다(K19-b). 표준 줄끼리 견주면 다음 해 판이 태그를 바로잡은 회사(삼성전기)의 고친 값이 「다시 실었다」로 읽혀 그날부터 모른다
     */
    static List<com.stockscreener.pipeline.datasource.FlowBasis> flowBases(int year, Map<StatementPeriod, Pending> pending,
                                                                           Map<StatementPeriod, Pending> next,
                                                                           Map<StatementPeriod, Pending> twoLater,
                                                                           Map<String, RawFinancialReport> reports, boolean krGate) {
        if (pending == null || reports == null || reports.isEmpty()) {
            return List.of();
        }
        Pending reference = java.util.stream.Stream.of(StatementPeriod.Q1, StatementPeriod.Q2, StatementPeriod.Q3, StatementPeriod.FY)
                .map(pending::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (reference == null) {
            return List.of();
        }
        // 연결 · 별도 기저는 가리지 않는다(주요계정 어댑터와 같다) — 뒤 보고서가 다른 기저로 실은 것도 「다시 실었다」다. 통화는 같아야 한다
        // (통화가 갈린 해는 ADR-0110이 다룬다 — 원화 · 달러를 견주면 모든 칸이 대체로 걸린다 · 01032486).
        String currency = currencyOf(reference.list());
        java.util.function.Function<Pending, Pending> same = p -> p != null && currency != null && currency.equals(currencyOf(p.list())) ? p : null;
        Pending q1 = same.apply(pending.get(StatementPeriod.Q1));
        Pending h1 = same.apply(pending.get(StatementPeriod.Q2));
        Pending q3 = same.apply(pending.get(StatementPeriod.Q3));
        Pending fy = same.apply(pending.get(StatementPeriod.FY));
        Pending nq1 = same.apply(next.get(StatementPeriod.Q1));
        Pending nh1 = same.apply(next.get(StatementPeriod.Q2));
        Pending nq3 = same.apply(next.get(StatementPeriod.Q3));
        Pending nfy = same.apply(next.get(StatementPeriod.FY));
        Pending n2fy = same.apply(twoLater.get(StatementPeriod.FY));
        Map<String, DartFlowBasis.Values> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, java.util.Set<String>> e : FLOW_COLUMNS.entrySet()) {
            java.util.Set<String> ids = e.getValue();
            boolean zero = ZERO_WHEN_ABSENT.contains(e.getKey());
            if (krGate && "net_income_controlling".equals(e.getKey())) {
                values.put(e.getKey(), new DartFlowBasis.Values(
                        controlling(q1, ids, "thstrm_amount", "thstrm_amount", "thstrm_amount"),
                        controlling(h1, ids, "thstrm_amount", "thstrm_add_amount", "thstrm_amount"),
                        controlling(h1, ids, "thstrm_add_amount", "thstrm_add_amount", "thstrm_amount"),
                        controlling(q3, ids, "thstrm_amount", "thstrm_add_amount", "thstrm_amount"),
                        controlling(q3, ids, "thstrm_add_amount", "thstrm_add_amount", "thstrm_amount"),
                        controlling(fy, ids, "thstrm_amount", "thstrm_amount", "thstrm_amount"),
                        firstOf(controlling(nq1, ids, "frmtrm_q_amount", "frmtrm_add_amount", "frmtrm_q_amount"),
                                controlling(nq1, ids, "frmtrm_add_amount", "frmtrm_add_amount", "frmtrm_q_amount")),
                        controlling(nh1, ids, "frmtrm_q_amount", "frmtrm_add_amount", "frmtrm_q_amount"),
                        controlling(nh1, ids, "frmtrm_add_amount", "frmtrm_add_amount", "frmtrm_q_amount"),
                        controlling(nq3, ids, "frmtrm_q_amount", "frmtrm_add_amount", "frmtrm_q_amount"),
                        controlling(nq3, ids, "frmtrm_add_amount", "frmtrm_add_amount", "frmtrm_q_amount"),
                        controlling(nfy, ids, "frmtrm_amount", "frmtrm_amount", "frmtrm_amount"),
                        controlling(n2fy, ids, "bfefrmtrm_amount", "bfefrmtrm_amount", "bfefrmtrm_amount")));
                continue;
            }
            values.put(e.getKey(), new DartFlowBasis.Values(
                    amount(q1, ids, "thstrm_amount", zero), amount(h1, ids, "thstrm_amount", zero),
                    amount(h1, ids, "thstrm_add_amount", zero), amount(q3, ids, "thstrm_amount", zero),
                    amount(q3, ids, "thstrm_add_amount", zero), amount(fy, ids, "thstrm_amount", zero),
                    // 전체 재무제표 분기 손익의 전기 3개월 칸은 frmtrm_q_amount다(frmtrm_amount는 비어 있다 — 주요계정과 다르다). 1분기는 3개월 = 누계.
                    firstOf(amount(nq1, ids, "frmtrm_q_amount", zero), amount(nq1, ids, "frmtrm_add_amount", zero)),
                    amount(nh1, ids, "frmtrm_q_amount", zero), amount(nh1, ids, "frmtrm_add_amount", zero),
                    amount(nq3, ids, "frmtrm_q_amount", zero), amount(nq3, ids, "frmtrm_add_amount", zero),
                    amount(nfy, ids, "frmtrm_amount", zero), amount(n2fy, ids, "bfefrmtrm_amount", zero)));
        }
        for (Map.Entry<String, java.util.Set<String>> e : ANNUAL_ONLY.entrySet()) {
            boolean cash = !"income_tax_expense".equals(e.getKey()) && !"profit_before_tax".equals(e.getKey());
            // capex · 법인세는 부호 표기가 보고서마다 갈린다(같은 금액이 다음 보고서에서 음수 — 법인세 90/191 · capex 15/29 · 반증 안 렌즈) —
            // 소비자도 절댓값 · 부호 정규화로 읽으므로(FreeCashFlow · effectiveTaxRateOf) 크기로 견준다.
            boolean magnitude = "capex".equals(e.getKey()) || "income_tax_expense".equals(e.getKey());
            values.put(e.getKey(), new DartFlowBasis.Values(null, null, null, null, null,
                    abs(annualAmount(fy, e.getValue(), "thstrm_amount", cash), magnitude), null, null, null, null, null,
                    abs(annualAmount(nfy, e.getValue(), "frmtrm_amount", cash), magnitude),
                    abs(annualAmount(n2fy, e.getValue(), "bfefrmtrm_amount", cash), magnitude)));
        }
        if (krGate) {
            // 계획 K12 D1-b: 뒤 보고서가 연결 중단영업을 0 아닌 값으로 실으며 지배 중단 줄을 안 실었으면 그 열의 지배 중단은 0이 아니라 모름이다
            // (부재 = 0은 연결 중단도 0일 때만 선다) — 0으로 읽으면 「0으로 다시 실었다」 · 「0과 닫혔다」가 된다.
            DartFlowBasis.Values dic = values.get("discontinued_income_controlling");
            values.put("discontinued_income_controlling", new DartFlowBasis.Values(dic.q1(), dic.q2(), dic.h1(), dic.q3(), dic.nine(),
                    dic.fy(), dic.priorQ1(), dic.priorQ2(), dic.priorH1(), dic.priorQ3(), dic.priorNine(),
                    controllingDiscontinuedUnknown(nfy, "frmtrm_amount") ? null : dic.priorFy(),
                    controllingDiscontinuedUnknown(n2fy, "bfefrmtrm_amount") ? null : dic.twoYearsPriorFy()));
        }
        DartFlowBasis.Dates dates = new DartFlowBasis.Dates(published(reports.get("Q1")), published(reports.get("Q2")),
                published(reports.get("Q3")), published(reports.get("FY")), filed(nq1, year + 1), filed(nh1, year + 1),
                filed(nq3, year + 1), filed(nfy, year + 1), filed(n2fy, year + 2));
        Map<String, Map<String, java.time.LocalDate>> verdict = DartFlowBasis.judge(values, dates);
        // 지배주주 순이익 표준 줄이 그 해 판에 없으면(지배 몫을 비표준 줄 · 계속 + 중단으로 지은 판) 이 소스의 순이익 판정을 따른다. 순이익 칸
        // 자체는 주요계정 어댑터가 싣는다.
        boolean ownControlling = values.get("net_income_controlling").q1() != null || values.get("net_income_controlling").fy() != null;
        Map<String, Map<String, java.time.LocalDate>> owned = new java.util.HashMap<>();
        verdict.forEach((label, byColumn) -> {
            Map<String, java.time.LocalDate> mine = new java.util.HashMap<>(byColumn);
            java.time.LocalDate income = mine.remove("net_income");
            if (!ownControlling) {
                mine.remove("net_income_controlling");
                if (income != null) {
                    mine.put("net_income_controlling", income);
                }
            }
            if (!mine.isEmpty()) {
                owned.put(label, mine);
            }
        });
        java.util.Set<String> judged = new java.util.LinkedHashSet<>(FLOW_COLUMNS.keySet());
        judged.addAll(ANNUAL_ONLY.keySet());
        judged.remove("net_income");
        Map<String, List<com.stockscreener.pipeline.datasource.FlowBasis.Restatement>> restated =
                new java.util.HashMap<>(DartFlowBasis.restatedFiscalYear(values, dates));
        restated.remove("net_income");
        // capex · 법인세는 크기로 견줬다(부호 표기가 갈린다) — 그 값을 새 FY로 넣으면 저장 부호 규약이 뒤집힌다. 그 둘은 비우기만 한다.
        restated.remove("capex");
        restated.remove("income_tax_expense");
        if (!ownControlling) {
            restated.remove("net_income_controlling");
        }
        Map<String, List<java.time.LocalDate>> closed = new java.util.HashMap<>();
        if (krGate) {
            closed.putAll(DartFlowBasis.closedFiscalYear(values, dates));
            List<java.time.LocalDate> income = closed.remove("net_income");
            // 지배 몫 표준 줄이 없는 해는 대체와 같이 이 소스의 순이익 닫힘을 따른다.
            if (!ownControlling) {
                closed.remove("net_income_controlling");
                if (income != null) {
                    closed.put("net_income_controlling", income);
                }
            }
        }
        return DartFlowBasis.bases(year, reports, judged, owned, restated, closed);
    }

    /** 그 열에 지배 중단 줄이 없는데 연결 중단은 0이 아닌가(계획 K12 D1-b) — 그러면 그 열의 지배 중단은 모른다. */
    private static boolean controllingDiscontinuedUnknown(Pending p, String field) {
        if (p == null) {
            return false;
        }
        List<JsonNode> income = incomeOf(p.list());
        BigDecimal consolidated = DartFullAccountMapper.findBy(income, DartFullAccountMapper.ID_DISCONTINUED, "account_id", field);
        return consolidated != null && consolidated.signum() != 0
                && DartFullAccountMapper.findBy(income, DartFullAccountMapper.ID_DISCONTINUED_CONTROLLING, "account_id", field) == null;
    }

    private static BigDecimal amount(Pending p, java.util.Set<String> ids, String field, boolean zeroWhenAbsent) {
        if (p == null) {
            return null;
        }
        List<JsonNode> income = incomeOf(p.list());
        BigDecimal v = DartFullAccountMapper.findBy(income, ids, "account_id", field);
        if (v == null && zeroWhenAbsent && !income.isEmpty() && income.stream().noneMatch(r -> ids.contains(r.path("account_id").asString("")))) {
            return BigDecimal.ZERO;
        }
        return v;
    }

    /** 판 비교의 지배주주 순이익 칸 — 원장 둘이 표준 줄을 반박하면 자본변동표가 확인한 줄({@link DartFullAccountMapper#confirmedControlling}). */
    private static BigDecimal controlling(Pending p, java.util.Set<String> ids, String field, String cumulativeField, String equityField) {
        if (p != null && p.consolidated()) {
            BigDecimal confirmed = DartFullAccountMapper.confirmedControlling(incomeOf(p.list()),
                    DartFullAccountMapper.sections(p.list(), DartFullAccountMapper.SJ_EQUITY_CHANGES), field, cumulativeField, equityField);
            if (confirmed != null) {
                return confirmed;
            }
        }
        return amount(p, ids, field, false);
    }

    private static BigDecimal firstOf(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
    }

    /** 응답 행의 통화가 하나면 그 통화 · 아니면 {@code null}. */
    private static String currencyOf(JsonNode list) {
        String found = null;
        for (JsonNode row : list) {
            String c = row.path("currency").asString("");
            if (c.isEmpty()) {
                continue;
            }
            if (found != null && !found.equals(c)) {
                return null;
            }
            found = c;
        }
        return found;
    }

    private static BigDecimal abs(BigDecimal v, boolean magnitude) {
        return v == null || !magnitude ? v : v.abs();
    }

    private static BigDecimal annualAmount(Pending p, java.util.Set<String> ids, String field, boolean cashFlow) {
        if (p == null) {
            return null;
        }
        List<JsonNode> rows = cashFlow ? DartFullAccountMapper.sections(p.list(), DartFullAccountMapper.SJ_CASH_FLOW) : incomeOf(p.list());
        return DartFullAccountMapper.findBy(rows, ids, "account_id", field);
    }

    private static java.time.LocalDate filed(Pending p, int year) {
        return p == null ? null : DartReportPeriods.publishedAt(p.list().get(0), year);
    }

    private static List<JsonNode> incomeOf(JsonNode list) {
        return DartFullAccountMapper.sections(list, DartFullAccountMapper.SJ_INCOME, DartFullAccountMapper.SJ_COMPREHENSIVE_INCOME);
    }

    private static java.time.LocalDate published(RawFinancialReport report) {
        return report == null ? null : report.publishedAt();
    }

    /** 못 정한 칸은 「없다」 판정으로 실어 저장의 옛 값을 지운다(소스 행의 null은 저장이 안 지운다). */
    private static java.util.Set<String> discontinuedAbsence(java.util.Set<String> absent, BigDecimal consolidated,
                                                            BigDecimal controlling) {
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(absent);
        if (consolidated == null) {
            merged.add(RawFinancialReport.DISCONTINUED_INCOME);
        }
        if (controlling == null) {
            merged.add(RawFinancialReport.DISCONTINUED_INCOME_CONTROLLING);
        }
        return merged;
    }

    /**
     * <b>지배주주 순이익의 부재가 판정인가.</b> 셋을 다 만족해야 참이다 —
     * ① 값을 못 만들었고 ② <b>CFS로 물었고</b> ③ 그 응답에 <b>연결 총액은 있었다.</b>
     *
     * <p>②가 없으면 별도재무제표의 원리적 부재가 「없다」로 읽혀 저장을 지우고,
     * ③이 없으면 손익을 아예 안 실은 판이 같은 일을 한다.
     */
    private static java.util.Set<String> assertedAbsence(BigDecimal controllingIncome,
                                                         List<JsonNode> income,
                                                         boolean gateOpen,
                                                         boolean consolidated) {
        if (controllingIncome != null || gateOpen || !consolidated) {
            return java.util.Set.of();
        }
        BigDecimal total = DartFullAccountMapper.find(
                income, DartFullAccountMapper.ID_NET_INCOME, DartAccountMapper.NET_INCOME);
        return total == null
                ? java.util.Set.of()
                : java.util.Set.of(RawFinancialReport.NET_INCOME_CONTROLLING);
    }

    /**
     * 4분기 <b>잔액</b>만 담은 보고서. 손익과 현금흐름은 비운다.
     *
     * <p>4분기 보고서는 존재하지 않으므로 그 행은 원래 사업보고서에서 유도된 것이고, 이 어댑터가
     * 사업보고서만 다시 받으면 그 행의 결손은 <b>영영 남는다.</b> dart-financial.md의 「계정 집합이 회사마다
     * 다르다」 절의 이익잉여금이 정확히 그
     * 자리다 — 채우지 않으면 {@code ALTMAN_Z}가 4분기 말을 최신으로 보는 구간(대략 1월~1분기
     * 공시일)에서만 값을 내지 못하고, 그 구멍은 보존 창 한가운데에 생겨 원인을 찾기 어렵다.
     *
     * <p>IMPORTANT — <b>잔액과 지배주주 순이익만</b> 싣는다. 재무상태표는 시점 값이라 연말
     * 잔액이 곧 4분기 말 잔액이지만, 나머지 손익과 현금흐름은 <b>유량</b>이라 사업보고서 값을
     * 그대로 쓰면 1년치가 한 분기에 들어간다. 비워 두면 {@code fillMissing}이 건드리지 않아
     * 주요계정이 만든 기존 유도값이 그대로 남는다.
     *
     * <p><b>지배주주 순이익만 예외인 이유는 주요계정이 그것을 원리적으로 못 만들기 때문이다</b>
     * (ADR-0098). 그쪽의 {@code deriveFourthQuarter}는 {@code FY − 3분기 누적}으로 Q4를 유도하는데,
     * 요약본에는 지분을 가르는 계정이 아예 없다. 그래서 <b>여기서 같은 산술을 한다</b> —
     * 누적 컬럼 대신 이미 만든 {@code Q1+Q2+Q3}(각 3개월)를 더해 뺀다. 둘은 같은 값이다
     * (삼성전자 2025 연결: {@code 8.2229+5.1164+12.2257 = 25.5650}, {@code 45.2068−25.5650 = 19.6417}로
     * 주요계정의 유도값과 일치).
     *
     * <p><b>Q4가 없으면 이 결정 전체가 무력해진다.</b> TTM은 최근 4분기이고 12월 결산 종목의
     * 그 창에 Q4가 반드시 들어가는데, {@code FinancialMath}의 폴백이 <b>창 단위</b>라 한 분기만
     * 비어도 넷 다 연결 총액으로 간다 — <b>실측으로 Q4 채움이 0%일 때 PER이 한 종목도 안 바뀌었다.</b>
     *
     * <p>MUST NOT: 분기를 요청하지 않은 경로에서 부르지 않는다. 전 종목 연간 적재가 이것을
     * 함께 내면 4분기 행이 <b>없던 종목·연도에 잔액만 있는 행</b>이 생긴다.
     *
     * @param thisYear 같은 회계연도의 FY·Q1~Q3 보고서. Q4 지배주주 순이익의 유도 재료다
     */
    private RawFinancialReport toFourthQuarterBalance(JsonNode list, int year, SecurityRef security,
                                                      List<RawFinancialReport> thisYear) {
        List<JsonNode> balance = DartFullAccountMapper.sections(
                list, DartFullAccountMapper.SJ_BALANCE);
        JsonNode any = list.get(0);
        LocalDate[] range = DartReportPeriods.periodRange(year, "Q4");

        BigDecimal retainedEarnings = DartFullAccountMapper.find(
                balance, DartFullAccountMapper.ID_RETAINED_EARNINGS, DartAccountMapper.RETAINED_EARNINGS);
        if (retainedEarnings == null) {
            retainedEarnings = DartFullAccountMapper.retainedEarningsFromEquityChanges(
                    list, security.ticker(), year, "Q4");
        }

        return new RawFinancialReport(
                year, "Q4", range[0], range[1],
                DartReportPeriods.publishedAt(any, year),
                any.path("reprt_code").asString(null),
                any.path("currency").asString("KRW"),
                // 나머지 유량은 비운다 — 위 IMPORTANT 참조.
                null, null, null,
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_ASSETS, DartAccountMapper.TOTAL_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_EQUITY, DartAccountMapper.TOTAL_EQUITY),
                DartFullAccountMapper.controllingEquity(balance),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_TOTAL_LIABILITIES,
                        DartAccountMapper.TOTAL_LIABILITIES),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_ASSETS,
                        DartAccountMapper.CURRENT_ASSETS),
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CURRENT_LIABILITIES,
                        DartAccountMapper.CURRENT_LIABILITIES),
                retainedEarnings,
                null, null, null, null,
                // 매출원가는 유량이라 여기서 비운다 — 위 IMPORTANT 참조.
                null,
                // 현금및현금성자산은 잔액이므로 4분기 행에도 담는다.
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_CASH_AND_EQUIVALENTS, null),
                // 자본적지출은 유량이고 연간만 담는다 — 아래 capex() 참조.
                null,
                // 재고자산은 잔액이므로 4분기 행에도 담는다. capex와 갈리는 자리다.
                DartFullAccountMapper.find(
                        balance, DartFullAccountMapper.ID_INVENTORIES, null),
                // 손익이지만 이것만 예외다 — 주요계정이 원리적으로 못 만든다(위 javadoc).
                deriveFourthQuarterControlling(thisYear, year),
                null, null, null, null, null, null, null, null,
                // 세율 재료는 유량이라 여기서 비운다. 세후 지표가 직전 회계연도 세율을 쓰므로
                // Q4를 유도할 이유가 없다 — netIncomeControlling만 예외인 사정이 여기엔 없다.
                null, null,
                // **MUST NOT: 여기에 `derived = true`를 주지 않는다 — 2026-09-22에 넣어 보고 되돌렸다.**
                //
                // 이 메서드는 이름 그대로 **재무상태표 Q4**다. 손익은
                // `deriveFourthQuarterControlling` 하나뿐이고 매출·영업이익·순이익은 **언제나
                // null**을 낸다. 그런데 그 칸을 채운 것은 **주요계정 어댑터의 유도 Q4**
                // (`DartFinancialAdapter.deriveFourthQuarter`)다.
                //
                // `derived = true`를 주면 `FinancialWriter.fillMissing`의 `toClear`가 열리고,
                // 이 호출의 FY·Q1~Q3가 매출을 냈으므로 `managedColumns`가 그 컬럼을 관할로
                // 판정한다 → **Q4에서 null인 손익이 저장에서 지워진다.** 실측(2026-09-22 ·
                // KOSDAQ 적재 중단 시점): **975건 · 142종목**의 매출·영업이익·순이익이 그렇게
                // 날아갔다. `managedColumns` javadoc의 「수요일이 채우고 목요일이 지우는 왕복」이
                // 방향만 바뀌어 재현된 것이다.
                //
                // **열려면 손익 컬럼을 이 메서드가 함께 만들어야 한다** — 그 전에는 못 연다.
                false,
                java.util.Set.of());
    }

    /**
     * Q4의 <b>지배기업 소유주 귀속</b> 순이익 = {@code FY − (Q1 + Q2 + Q3)}.
     *
     * <p>MUST: 넷 중 하나라도 없으면 {@code null}을 돌린다. 빠진 분기를 0으로 보면 그만큼
     * Q4가 부풀고, <b>그 값은 그럴듯해 드러나지 않는다.</b> null이면 {@code FinancialMath}의
     * 창 단위 폴백이 그 종목의 TTM을 통째로 연결 총액으로 돌린다 — 뜻이 섞이는 것보다 낫다.
     */
    static BigDecimal deriveFourthQuarterControlling(List<RawFinancialReport> thisYear,
                                                             int year) {
        BigDecimal annual = null;
        String annualCurrency = null;
        BigDecimal quarterSum = BigDecimal.ZERO;
        int quarters = 0;
        boolean sameCurrency = true;
        for (RawFinancialReport report : thisYear) {
            if (report.fiscalYear() != year || report.netIncomeControlling() == null) {
                continue;
            }
            if (StatementPeriod.FY.label().equals(report.fiscalPeriod())) {
                annual = report.netIncomeControlling();
                annualCurrency = report.currencyCode();
            } else {
                quarterSum = quarterSum.add(report.netIncomeControlling());
                quarters++;
            }
        }
        if (annual == null || quarters != 3) {
            return null;
        }
        for (RawFinancialReport report : thisYear) {
            if (report.fiscalYear() == year && report.netIncomeControlling() != null
                    && !StatementPeriod.FY.label().equals(report.fiscalPeriod())
                    && !report.currencyCode().equals(annualCurrency)) {
                sameCurrency = false;
            }
        }
        // MUST: 통화가 갈리면 내지 않는다(ADR-0110). 이 뺄셈도 FY − 3분기라
        // DartFinancialAdapter·SecEdgarFacts와 **같은 자리**이고, 넷 중 하나만 빼먹으면
        // 그 경로로만 오염이 다시 들어온다 — 실제로 이 자리가 계획에서 두 번 빠졌다.
        return sameCurrency ? annual.subtract(quarterSum) : null;
    }

    /**
     * 영업활동현금흐름. <b>연간만</b> 낸다.
     *
     * <p>분기 값을 버리는 것이 아깝게 보이지만, 그 값이 3개월인지 누적인지 모르는 채로
     * 저장하면 나중에 그것을 쓰는 쪽이 <b>모른다는 사실조차 모른다.</b> 실측이 그 절(dart-financial.md의 「전체
     * 재무제표」 절)에
     * 적히면 그때 연다.
     */
    /**
     * 법인세비용. <b>연간만</b> 낸다.
     *
     * <p><b>MUST: 이 가드를 지우지 않는다.</b> {@code toReport}는 FY 전용이 아니라
     * {@code for (StatementPeriod period : values())} 루프 안에서 <b>Q1~Q3에도 불리고</b>,
     * 정기 재적재의 표준 명령이 {@code --all-quarters=true}다({@code JobFreshnessPolicy}).
     * 가드가 없으면 <b>Q1~Q3에만 값이 들어가고 Q4가 NULL</b>이 되는데, 그것은
     * {@code cost_of_sales}가 지금 있는 상태이고 그 컬럼을 TTM으로 합치는 쪽에서
     * <b>한 칸이 비어 값이 통째로 안 난다.</b>
     *
     * <p>Q4를 유도하지 않는 이유는 <b>세후 지표가 직전 회계연도 유효세율을 쓰기 때문</b>이다.
     * 분기 세율이 필요하지 않으므로 만들지 않는다 — 불가능해서가 아니다
     * ({@code deriveFourthQuarterControlling}이 같은 산술을 이미 돌린다).
     */
    private static BigDecimal incomeTaxExpense(List<JsonNode> income, StatementPeriod period) {
        if (period != StatementPeriod.FY) {
            return null;
        }
        return DartFullAccountMapper.find(
                income, DartFullAccountMapper.ID_INCOME_TAX_EXPENSE, null);
    }

    /** 세전이익. 법인세비용과 같은 이유로 <b>연간만</b> 낸다 — 둘이 같은 창에 있어야 세율이 선다. */
    private static BigDecimal profitBeforeTax(List<JsonNode> income, StatementPeriod period) {
        if (period != StatementPeriod.FY) {
            return null;
        }
        return DartFullAccountMapper.find(
                income, DartFullAccountMapper.ID_PROFIT_BEFORE_TAX, null);
    }

    private static BigDecimal operatingCashFlow(List<JsonNode> cashFlow, StatementPeriod period) {
        if (period != StatementPeriod.FY) {
            return null;
        }
        return DartFullAccountMapper.operatingCashFlow(cashFlow);
    }

    /**
     * 자본적지출(유형자산의 취득). <b>연간만</b> 낸다 — 영업활동현금흐름과 같은 이유다.
     *
     * <p>둘이 같은 창에서만 값을 갖는 것이 중요하다. 잉여현금흐름은 둘의 차이인데 한쪽만
     * 분기에 값이 있으면 <b>기간이 다른 두 수를 빼게 된다.</b>
     *
     * <p>MUST NOT: 무형자산의 취득을 여기 더하지 않는다. 실측에서 둘 다 8/8로 오지만
     * (dart-financial.md의 「계정별 가용성」 절) 합칠지는 지표가 정할 문제이고, 원자료 계층에서 합치면
     * <b>나중에 가를 수
     * 없다.</b> 원자료만 담는다는 선은 여기서도 지킨다.
     */
    private static BigDecimal capex(List<JsonNode> cashFlow, StatementPeriod period) {
        if (period != StatementPeriod.FY) {
            return null;
        }
        return DartFullAccountMapper.find(cashFlow, DartFullAccountMapper.ID_CAPEX, null);
    }

    /** 받은 행과 그것을 받는 데 든 호출 수. 연결이 비어 별도로 다시 부르면 2다. */
    /**
     * @param consolidated <b>연결재무제표(CFS)로 받았는가.</b> 지금까지 버리던 사실이다 —
     *                     클래스 javadoc이 *"어느 쪽을 받았는지는 요청만 안다"*라 적었고,
     *                     그 「요청만 안다」를 <b>여기에 실어야</b> 뒤 단계가 쓸 수 있다.
     *                     별도재무제표(OFS)에는 비지배 행이 <b>원리적으로</b> 없으므로
     *                     그 부재는 「비지배가 없다」의 증거가 아니다.
     */
    private record Fetched(JsonNode list, int calls, boolean consolidated) {
    }

    /** 1패스가 모은 응답 한 건. 2패스가 종목 단위 판정을 만들고 3패스가 보고서를 짓는다. */
    record Pending(int year, StatementPeriod period, JsonNode list, boolean consolidated) {
    }
}
