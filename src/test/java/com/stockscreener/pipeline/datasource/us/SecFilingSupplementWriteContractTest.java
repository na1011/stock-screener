package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.collector.FinancialCollector;
import com.stockscreener.pipeline.collector.FinancialColumnAbsenceWriter;
import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.collector.NoOpIndicatorInvalidationPort;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.support.JpaSliceTest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공시 보충의 실패 계약을 <b>운영과 같은 수집 경로</b>(어댑터 → 보충 → 저장 표 → {@link FinancialCollector} → Writer)로
 * 묻는다(ADR-0173 · RS2). 정기 경로와 같게 이른 판 넣기를 끈 채(FILL_MISSING · {@code insertEarlierVintages=false}) 돈다.
 *
 * <p>모양: FY2025를 원본 10-K X(2026-02-20 · 매출 950)와 정정 10-K/A Y(2026-04-20 · 매출 1,000)가 싣는다. companyfacts는
 * Y만 싣고 X를 빠뜨린다 — (가)의 실물 모양이다(BE·GRMN·INCY 등 정정 쌍).
 */
@JpaSliceTest
@Import({FinancialWriter.class, FinancialColumnAbsenceWriter.class,
        NoOpIndicatorInvalidationPort.DefaultInvalidationPortConfig.class})
class SecFilingSupplementWriteContractTest {

    private static final String X = "0000000001-26-000020";
    private static final String Y = "0000000001-26-000040";
    private static final String X_FILED = "2026-02-20";
    private static final String Y_FILED = "2026-04-20";
    private static final int FROM = 2024;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FinancialWriter writer;

    @Autowired
    private FinancialColumnAbsenceWriter absenceWriter;

    private MockWebServer server;
    private final Map<String, MockResponse> responses = new ConcurrentHashMap<>();
    private FinancialCollector collector;
    /** 보충이 없는 옛 수집기 — 배포 전 운영이 쌓은 상태를 만든다. */
    private FinancialCollector beforeSupplement;
    private SecurityRef fix;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                MockResponse response = responses.get(request.getPath());
                return response != null ? response : new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 0, 0);
        String base = server.url("/").toString();
        SecFilingSupplement supplement = new SecFilingSupplement(
                new SecFilingClient(factory, properties, limiter, base, base),
                new SecFilingSupplementStore(jdbcTemplate));
        SecEdgarFinancialAdapter adapter = new SecEdgarFinancialAdapter(factory, properties, limiter,
                new SecTickerCikCache(factory, properties, limiter, base, 1), base, false, supplement);
        DataSourceRegistry registry = new DataSourceRegistry(List.of(), List.of(adapter), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        collector = new FinancialCollector(registry, writer, absenceWriter);
        SecEdgarFinancialAdapter oldAdapter = new SecEdgarFinancialAdapter(factory, properties, limiter,
                new SecTickerCikCache(factory, properties, limiter, base, 1), base);
        beforeSupplement = new FinancialCollector(new DataSourceRegistry(List.of(), List.of(oldAdapter), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), writer, absenceWriter);
        long stockId = jdbcTemplate.queryForObject(
                "INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'FIX', '픽스처') RETURNING id", Long.class);
        fix = new SecurityRef(stockId, "US", "FIX");

        ok("/files/company_tickers_exchange.json", """
                {"fields":["cik","name","ticker","exchange"],"data":[[1,"픽스처","FIX","Nasdaq"]]}""");
        ok("/submissions/CIK0000000001.json", """
                {"cik":"1","filings":{"recent":{
                  "accessionNumber":["%s","%s"],"form":["10-K","10-K/A"],"filingDate":["%s","%s"],
                  "reportDate":["2025-12-31","2025-12-31"],"isXBRL":[1,1]},"files":[]}}"""
                .formatted(X, Y, X_FILED, Y_FILED));
        ok(indexPath(X), """
                {"directory":{"item":[{"name":"fix-20251231_htm.xml"},{"name":"fix-20251231_lab.xml"},
                  {"name":"FilingSummary.xml"}]}}""");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("실패가 먼저 오고 나중에 성공해도, 성공 한 번과 같은 판 집합에 닿는다 — 회복 표지가 그 판을 정기 경로에서 넣는다")
    void failureThenRecoveryEqualsSuccessOnce() throws Exception {
        companyFacts(false);
        status(instancePath(X), 503);
        collect();
        assertThat(rows()).as("X를 못 실은 실행 — Y의 판만").containsExactly(Y_FILED + "|1000|true|0");
        assertThat(storeStatus(X)).isEqualTo("FAILED");

        ok(instancePath(X), instance(X_FILED));
        collect();

        assertThat(rows()).as("성공 — X의 판이 이른 판으로 들어오고 번호가 공시일 순이다")
                .containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");
        assertThat(storeStatus(X)).isEqualTo("OK");
    }

    @Test
    @DisplayName("배포 시점처럼 늦은 판이 이미 있어도 보충의 첫 성공이 원본 판을 넣는다 — 실패 먼저와 같은 판 집합")
    void firstSuccessOverAPreloadedLaterVintage() throws Exception {
        companyFacts(false);
        beforeSupplement.collectDetailed(fix, FROM, FinancialWriter.WriteMode.FILL_MISSING, false);
        assertThat(rows()).as("보충 없는 옛 코드가 쌓은 상태 — Y의 판만").containsExactly(Y_FILED + "|1000|true|0");

        ok(instancePath(X), instance(X_FILED));
        collect();

        assertThat(rows()).containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");
    }

    @Test
    @DisplayName("실패 뒤 companyfacts가 그 공시를 실었고 그 사이 APPEND_ONLY 실행이 끼어도 원본 판이 들어온다 — 열림이 한 번에 소비되지 않는다")
    void coveredFilingStillOpensAfterAnAppendOnlyRun() throws Exception {
        companyFacts(false);
        status(instancePath(X), 503);
        collect();
        assertThat(rows()).containsExactly(Y_FILED + "|1000|true|0");

        companyFacts(true);
        // 수동 initial-load(APPEND_ONLY) — Writer가 이른 판 열림을 안 쓴다.
        collector.collectDetailed(fix, FROM, FinancialWriter.WriteMode.APPEND_ONLY, false);
        collect();

        assertThat(rows()).containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");
    }

    @Test
    @DisplayName("받은 공시를 companyfacts가 나중에 실어도 원본 판이 들어온다 — 받은 실행이 APPEND_ONLY뿐이었어도(실패였던 공시와 같은 판 집합)")
    void receivedFilingLaterCoveredStillOpensAfterAnAppendOnlyOnlyReceipt() throws Exception {
        companyFacts(false);
        beforeSupplement.collectDetailed(fix, FROM, FinancialWriter.WriteMode.FILL_MISSING, false);
        assertThat(rows()).containsExactly(Y_FILED + "|1000|true|0");

        // 보충으로 X를 받은 실행이 수동 initial-load(APPEND_ONLY)뿐이다 — Writer가 그 열림을 안 썼다.
        ok(instancePath(X), instance(X_FILED));
        collector.collectDetailed(fix, FROM, FinancialWriter.WriteMode.APPEND_ONLY, false);
        assertThat(rows()).as("APPEND_ONLY는 이른 판을 안 넣는다").containsExactly(Y_FILED + "|1000|true|0");

        companyFacts(true);
        collect();

        assertThat(rows()).containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");
    }

    @Test
    @DisplayName("companyfacts가 덮은 공시에 하루 늦은 filed를 붙여도 그 판이 들어온다 — 목록 날짜만 열면 뒤 판에 밀려 빠진다")
    void coveredFilingOpensTheDateCompanyFactsGivesIt() throws Exception {
        companyFacts(false);
        ok(instancePath(X), instance(X_FILED));
        collect();
        assertThat(rows()).containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");

        companyFacts(true, "2026-02-21");
        collect();

        assertThat(rows()).containsExactly("2026-02-21|950|false|0", Y_FILED + "|1000|true|1");
    }

    @Test
    @DisplayName("대조 — 성공이 먼저 오면 같은 판 집합이다(위 시험이 닿아야 할 곳)")
    void successOnce() throws Exception {
        companyFacts(false);
        ok(instancePath(X), instance(X_FILED));

        collect();

        assertThat(rows()).containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");
    }

    @Test
    @DisplayName("저장분을 새 읽기 규칙으로 다시 읽다 실패해도 옛 저장분을 쓴다 — 아무것도 안 지운다")
    void rereadFailureKeepsUsingTheStoredFacts() throws Exception {
        companyFacts(false);
        ok(instancePath(X), instance(X_FILED));
        collect();
        List<String> before = rows();

        jdbcTemplate.update("UPDATE sec_filing_supplement SET reader_version = 0 WHERE accession = ?", X);
        status(instancePath(X), 404);
        collect();

        assertThat(rows()).isEqualTo(before);
        assertThat(storeStatus(X)).as("옛 판 저장분을 덮지 않는다").isEqualTo("OK");
    }

    @Test
    @DisplayName("companyfacts가 실었던 공시를 빼고 보충도 실패하면 그 판을 지킨다 — 회수가 「여기 없는 공시일」로 읽지 않게")
    void protectsAVintageWhenCompanyFactsDropsItAndSupplementFails() throws Exception {
        companyFacts(true);
        collect();
        assertThat(rows()).containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");

        companyFacts(false);
        status(instancePath(X), 503);
        collect();

        assertThat(rows()).as("X의 판이 남는다").containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");
    }

    @Test
    @DisplayName("공시 목록을 못 받으면 회수를 멈추고 그 사실을 실행 결과로 낸다 — SUCCESS 뒤에 숨지 않게(실행 기록의 회수정지)")
    void listFailureStopsRetractionAndSaysSo() throws Exception {
        companyFacts(true);
        collect();
        status("/submissions/CIK0000000001.json", 503);
        companyFacts(false);

        FinancialWriter.Outcome outcome = collector.collectDetailed(fix, FROM, FinancialWriter.WriteMode.FILL_MISSING, false);

        assertThat(outcome.retractionStopped()).isTrue();
        assertThat(rows()).as("X의 판을 지운다는 근거가 없다 — 회수가 멈춘다")
                .containsExactly(X_FILED + "|950|false|0", Y_FILED + "|1000|true|1");
    }

    private void collect() throws Exception {
        collector.collectDetailed(fix, FROM, FinancialWriter.WriteMode.FILL_MISSING, false);
    }

    /** FY2025 판들 — 공시일|매출|최신|번호. */
    private List<String> rows() {
        return jdbcTemplate.query("""
                SELECT published_at, revenue, is_latest, revision_no FROM financial_statement
                WHERE stock_id = ? AND fiscal_year = 2025 AND fiscal_period = 'FY' ORDER BY published_at
                """, (rs, n) -> rs.getObject("published_at", LocalDate.class) + "|"
                + rs.getBigDecimal("revenue").stripTrailingZeros().toPlainString() + "|"
                + rs.getBoolean("is_latest") + "|" + rs.getInt("revision_no"), fix.stockId());
    }

    private String storeStatus(String accession) {
        return jdbcTemplate.queryForObject("SELECT status FROM sec_filing_supplement WHERE accession = ?",
                String.class, accession);
    }

    /** companyfacts — Y는 늘 싣고, {@code withX}면 X도 싣는다. */
    private void companyFacts(boolean withX) {
        companyFacts(withX, X_FILED);
    }

    /** companyfacts가 X에 붙이는 {@code filed}를 따로 준다 — 17:30 ET 뒤 접수면 목록 제출일보다 하루 늦다. */
    private void companyFacts(boolean withX, String xFiled) {
        String y = """
                {"start":"2025-01-01","end":"2025-12-31","val":1000,"accn":"%s","fy":2025,"fp":"FY","form":"10-K/A","filed":"%s"}"""
                .formatted(Y, Y_FILED);
        String x = """
                {"start":"2025-01-01","end":"2025-12-31","val":950,"accn":"%s","fy":2025,"fp":"FY","form":"10-K","filed":"%s"}"""
                .formatted(X, xFiled);
        ok("/api/xbrl/companyfacts/CIK0000000001.json", """
                {"cik":1,"entityName":"픽스처","facts":{"us-gaap":{"Revenues":{"units":{"USD":[%s]}}}}}"""
                .formatted(withX ? x + "," + y : y));
    }

    private static String instance(String filed) {
        return """
                <xbrl xmlns="http://www.xbrl.org/2003/instance" xmlns:g="http://fasb.org/us-gaap/2025"
                      xmlns:dei="http://xbrl.sec.gov/dei/2025" xmlns:iso4217="http://www.xbrl.org/2003/iso4217">
                  <context id="d"><entity><identifier scheme="http://www.sec.gov/CIK">0000000001</identifier></entity>
                    <period><startDate>2025-01-01</startDate><endDate>2025-12-31</endDate></period></context>
                  <unit id="usd"><measure>iso4217:USD</measure></unit>
                  <dei:DocumentType contextRef="d">10-K</dei:DocumentType>
                  <dei:DocumentFiscalYearFocus contextRef="d">2025</dei:DocumentFiscalYearFocus>
                  <dei:DocumentFiscalPeriodFocus contextRef="d">FY</dei:DocumentFiscalPeriodFocus>
                  <g:Revenues contextRef="d" unitRef="usd" decimals="-6">950</g:Revenues>
                </xbrl>""";
    }

    private void ok(String path, String body) {
        responses.put(path, new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", path.endsWith(".xml") ? "application/xml" : "application/json")
                .setBody(body));
    }

    private void status(String path, int code) {
        responses.put(path, new MockResponse().setResponseCode(code));
    }

    private static String indexPath(String accession) {
        return "/Archives/edgar/data/1/" + accession.replace("-", "") + "/index.json";
    }

    private static String instancePath(String accession) {
        return "/Archives/edgar/data/1/" + accession.replace("-", "") + "/fix-20251231_htm.xml";
    }
}
