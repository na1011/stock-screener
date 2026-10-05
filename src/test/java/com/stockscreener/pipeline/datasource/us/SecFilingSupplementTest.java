package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;
import com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;
import com.stockscreener.support.TestDatabaseGuard;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * companyfacts가 빠뜨린 공시를 인스턴스로 보충하는 계약(ADR-0173).
 *
 * <p>지키는 것은 넷이다 — <b>무엇을 보충하는가</b>(창 · 폼 · XBRL · companyfacts us-gaap 0) · <b>한 번 받으면 다시 안
 * 받는다</b> · <b>실패가 종목을 세우지 않고 판 보호로 나간다</b> · <b>실패였다가 성공하면 회복으로 알린다</b>.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 저장 표는 호출자 트랜잭션에 참여하는데, 시험에는 그 트랜잭션이
 * 없어 문장마다 커밋된다. 그래서 {@code @AfterEach}로 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class SecFilingSupplementTest {

    private static final long CIK = 1800;
    private static final String COVERED = "0000001800-26-000050";
    private static final String MISSING = "0000001800-26-000077";
    private static final String EIGHT_K = "0000001800-26-000080";
    private static final String NO_XBRL = "0000001800-26-000081";
    private static final String TOO_OLD = "0000001800-23-000010";
    private static final LocalDate MISSING_FILED = LocalDate.of(2026, 7, 28);
    private static final int FROM = 2025;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockWebServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    /** 경로마다 돌려줄 상태코드. 없으면 200. */
    private final Map<String, Integer> statusOf = new ConcurrentHashMap<>();
    private final Map<String, String> bodyOf = new ConcurrentHashMap<>();
    private SecFilingSupplement supplement;

    @BeforeEach
    void setUp() throws IOException {
        jdbcTemplate.update("DELETE FROM sec_filing_supplement WHERE cik = ?", CIK);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                paths.add(path);
                int status = statusOf.getOrDefault(path, 200);
                String body = bodyOf.get(path);
                if (status != 200 || body == null) {
                    return new MockResponse().setResponseCode(status == 200 ? 404 : status)
                            .setBody("<Error><Code>NoSuchKey</Code></Error>");
                }
                return new MockResponse().setResponseCode(200).setBody(body);
            }
        });
        server.start();
        DataSourceProperties properties = new DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        ExternalApiClientFactory factory = new ExternalApiClientFactory();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("sec-edgar", 0, 0);
        String base = server.url("/").toString();
        SecFilingClient client = new SecFilingClient(factory, properties, limiter, base, base);
        supplement = new SecFilingSupplement(client, new SecFilingSupplementStore(jdbcTemplate));

        bodyOf.put(submissionsPath(), submissions(List.of(
                filing(COVERED, "10-Q", "2026-05-01", 1),
                filing(MISSING, "10-Q", MISSING_FILED.toString(), 1),
                filing(EIGHT_K, "8-K", "2026-07-28", 1),
                filing(NO_XBRL, "10-Q", "2026-08-01", 0),
                filing(TOO_OLD, "10-K", "2023-02-01", 1)), List.of()));
        bodyOf.put(indexPath(MISSING), index("abt-20260630_cal.xml", "abt-20260630_htm.xml",
                "abt-20260630_lab.xml", "abt-20260630_pre.xml", "FilingSummary.xml", "abt-20260630.htm"));
        bodyOf.put(instancePath(MISSING, "abt-20260630_htm.xml"), instanceXml());
    }

    @AfterEach
    void cleanUp() throws IOException {
        jdbcTemplate.update("DELETE FROM sec_filing_supplement WHERE cik = ?", CIK);
        server.shutdown();
    }

    @Test
    @DisplayName("보충 대상은 창 안 · 보충 폼 · XBRL · companyfacts us-gaap 0인 공시뿐이다 — 8-K·XBRL 없음·창 밖·이미 실린 것은 안 받는다")
    void supplementsOnlyUncoveredPeriodicXbrlFilingsInTheWindow() throws Exception {
        ObjectNode facts = companyFacts();

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, facts, FROM, "t");

        assertThat(outcome.fetched()).isEqualTo(1);
        assertThat(outcome.failed()).isEmpty();
        assertThat(paths).filteredOn(p -> p.contains("/Archives/"))
                .allMatch(p -> p.contains(MISSING.replace("-", "")));
        assertThat(accessionsIn(facts)).contains(MISSING);
        assertThat(storedStatus(MISSING)).isEqualTo("OK");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sec_filing_supplement WHERE cik = ?",
                Long.class, CIK)).isEqualTo(1);
    }

    @Test
    @DisplayName("한 번 받은 공시는 다시 안 받는다 — 두 번째 실행은 목록 한 콜이고 저장분으로 같은 트리를 낸다")
    void reusesTheStoredFiling() throws Exception {
        ObjectNode first = companyFacts();
        supplement.supplement(CIK, first, FROM, "t");
        paths.clear();

        ObjectNode second = companyFacts();
        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, second, FROM, "t");

        assertThat(outcome.fromStore()).isEqualTo(1);
        assertThat(outcome.fetched()).isZero();
        assertThat(paths).containsExactly(submissionsPath());
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("결정적 실패(404)는 그 공시의 제출일을 지키고, 읽기 규칙이 같으면 다시 받지 않는다")
    void deterministicFailureProtectsItsDateWithoutRefetching() throws Exception {
        statusOf.put(indexPath(MISSING), 404);

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.failed()).containsExactly(MISSING);
        assertThat(outcome.unconfirmedVintages()).containsExactly(MISSING_FILED);
        assertThat(outcome.vintagesUncertain()).isFalse();
        assertThat(storedStatus(MISSING)).isEqualTo("FAILED");

        paths.clear();
        SecFilingSupplement.Outcome again = supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(again.unconfirmedVintages()).containsExactly(MISSING_FILED);
        assertThat(paths).containsExactly(submissionsPath());
    }

    @Test
    @DisplayName("일시 실패(5xx)는 두 번 다시 묻고, 다음 실행이 성공하면 회복으로 알린다")
    void transientFailureIsRetriedAndRecoveryIsReported() throws Exception {
        statusOf.put(indexPath(MISSING), 503);

        SecFilingSupplement.Outcome failed = supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(failed.unconfirmedVintages()).containsExactly(MISSING_FILED);
        assertThat(paths).filteredOn(p -> p.equals(indexPath(MISSING)))
                .as("처음 한 번 + 다시 두 번").hasSize(1 + SecFilingClient.TRANSIENT_RETRIES);

        statusOf.remove(indexPath(MISSING));
        SecFilingSupplement.Outcome recovered = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(recovered.recoveredVintages()).containsExactly(MISSING_FILED);
        assertThat(recovered.unconfirmedVintages()).isEmpty();
        assertThat(storedStatus(MISSING)).isEqualTo("OK");
    }

    @Test
    @DisplayName("다른 문서를 고르면 그 공시의 실패다 — 저장하지 않고 그 날짜를 지킨다")
    void anotherDocumentIsAFailureOfThatFiling() throws Exception {
        bodyOf.put(submissionsPath(), submissions(List.of(
                filing(MISSING, "10-K", MISSING_FILED.toString(), 1)), List.of()));

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.failed()).containsExactly(MISSING);
        assertThat(jdbcTemplate.queryForObject("SELECT failure_kind FROM sec_filing_supplement WHERE accession = ?",
                String.class, MISSING)).isEqualTo("MALFORMED");
    }

    @Test
    @DisplayName("인스턴스 후보가 하나로 안 갈리면 그 공시의 실패다 — 고를 근거가 없다")
    void ambiguousInstanceIsAFailure() throws Exception {
        bodyOf.put(indexPath(MISSING), index("a_htm.xml", "b.xml", "FilingSummary.xml"));

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.failed()).containsExactly(MISSING);
    }

    @Test
    @DisplayName("공시 목록을 못 받으면 저장분만 합치고 그 종목의 회수를 멈춘다 — 종목을 세우지 않는다")
    void listFailureMergesStoredAndMarksUncertain() throws Exception {
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        statusOf.put(submissionsPath(), 500);

        ObjectNode facts = companyFacts();
        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, facts, FROM, "t");

        assertThat(outcome.vintagesUncertain()).isTrue();
        assertThat(outcome.fromStore()).isEqualTo(1);
        assertThat(accessionsIn(facts)).contains(MISSING);
    }

    @Test
    @DisplayName("페이지는 받는 창에 닿을 때만 따라간다 — 창보다 오래된 페이지는 안 받는다")
    void followsOnlyPagesThatReachTheWindow() throws Exception {
        String recentPage = "CIK0000001800-submissions-001.json";
        String oldPage = "CIK0000001800-submissions-002.json";
        bodyOf.put(submissionsPath(), submissions(List.of(filing(COVERED, "10-Q", "2026-05-01", 1)), List.of(
                Map.of("name", recentPage, "filingFrom", "2024-01-01", "filingTo", "2026-04-30"),
                Map.of("name", oldPage, "filingFrom", "2010-01-01", "filingTo", "2023-12-31"))));
        bodyOf.put("/submissions/" + recentPage, columns(List.of(filing(MISSING, "10-Q", MISSING_FILED.toString(), 1))));

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.fetched()).isEqualTo(1);
        assertThat(paths).contains("/submissions/" + recentPage).doesNotContain("/submissions/" + oldPage);
    }

    @Test
    @DisplayName("보충 폼은 8-K가 없고 정기보고서 목록의 부분집합이다 — 그쪽에 8-K를 더해도 보충은 안 넓어진다")
    void supplementFormsAreIndependentOfTheReader() {
        assertThat(SecFilingSupplement.SUPPLEMENT_FORMS).doesNotContain("8-K", "8-K/A");
        assertThat(SecEdgarFacts.PERIODIC_FORMS).containsAll(SecFilingSupplement.SUPPLEMENT_FORMS);
    }

    private ObjectNode companyFacts() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"us-gaap":{"Revenues":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":10000000000,"accn":"%s","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"}]}}}}"""
                .formatted(COVERED));
    }

    private static List<String> accessionsIn(ObjectNode facts) {
        List<String> out = new ArrayList<>(SecFilingSupplement.usGaapAccessions(facts));
        out.sort(String::compareTo);
        return out;
    }

    private String storedStatus(String accession) {
        return jdbcTemplate.queryForObject("SELECT status FROM sec_filing_supplement WHERE accession = ?",
                String.class, accession);
    }

    private static String submissionsPath() {
        return "/submissions/CIK0000001800.json";
    }

    private static String indexPath(String accession) {
        return "/Archives/edgar/data/1800/" + accession.replace("-", "") + "/index.json";
    }

    private static String instancePath(String accession, String name) {
        return "/Archives/edgar/data/1800/" + accession.replace("-", "") + "/" + name;
    }

    private static Map<String, Object> filing(String accession, String form, String filed, int xbrl) {
        Map<String, Object> m = new HashMap<>();
        m.put("accessionNumber", accession);
        m.put("form", form);
        m.put("filingDate", filed);
        m.put("reportDate", "");
        m.put("isXBRL", xbrl);
        return m;
    }

    private static String submissions(List<Map<String, Object>> recent, List<Map<String, String>> pages) {
        Map<String, Object> filings = new HashMap<>();
        filings.put("recent", columnMap(recent));
        filings.put("files", pages);
        return MAPPER.writeValueAsString(Map.of("cik", "1800", "filings", filings));
    }

    private static String columns(List<Map<String, Object>> rows) {
        return MAPPER.writeValueAsString(columnMap(rows));
    }

    private static Map<String, List<Object>> columnMap(List<Map<String, Object>> rows) {
        Map<String, List<Object>> cols = new HashMap<>();
        for (String key : List.of("accessionNumber", "form", "filingDate", "reportDate", "isXBRL")) {
            List<Object> col = new ArrayList<>();
            rows.forEach(r -> col.add(r.get(key)));
            cols.put(key, col);
        }
        return cols;
    }

    private static String index(String... names) {
        List<Map<String, String>> items = new ArrayList<>();
        for (String name : names) {
            items.add(Map.of("name", name, "type", "text.gif", "size", "1"));
        }
        return MAPPER.writeValueAsString(Map.of("directory", Map.of("item", items)));
    }

    private static String instanceXml() throws IOException {
        return new ClassPathResource("fixtures/sec-edgar/instance-mini.xml")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
