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
    @DisplayName("받지 못한 것(404)은 그 날짜를 지키고 다음 실행이 다시 받는다 — 일시 404 한 번을 영구로 만들지 않는다")
    void notFoundProtectsItsDateAndIsRetriedNextRun() throws Exception {
        statusOf.put(indexPath(MISSING), 404);

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.failed()).containsExactly(MISSING);
        assertThat(outcome.unconfirmedVintages()).containsExactly(MISSING_FILED);
        assertThat(outcome.supplementedVintages()).isEmpty();
        assertThat(storedStatus(MISSING)).isEqualTo("FAILED");

        paths.clear();
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(paths).contains(indexPath(MISSING));
    }

    @Test
    @DisplayName("받은 파일을 해석하지 못한 것은 읽기 규칙이 같은 동안 다시 받지 않고 그 날짜만 지킨다")
    void unreadableIsNotRefetchedWhileTheReaderIsTheSame() throws Exception {
        bodyOf.put(indexPath(MISSING), index("a_htm.xml", "b.xml", "FilingSummary.xml"));

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(outcome.failed()).containsExactly(MISSING);
        assertThat(failureKind(MISSING)).isEqualTo(SecFilingSupplement.UNREADABLE);

        paths.clear();
        SecFilingSupplement.Outcome again = supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(again.unconfirmedVintages()).containsExactly(MISSING_FILED);
        assertThat(paths).containsExactly(submissionsPath());
    }

    @Test
    @DisplayName("일시 실패(5xx)는 두 번 다시 묻고, 다음 실행이 성공하면 그 판을 연다")
    void transientFailureIsRetriedAndTheVintageOpensOnSuccess() throws Exception {
        statusOf.put(indexPath(MISSING), 503);

        SecFilingSupplement.Outcome failed = supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(failed.unconfirmedVintages()).containsExactly(MISSING_FILED);
        assertThat(paths).filteredOn(p -> p.equals(indexPath(MISSING)))
                .as("처음 한 번 + 다시 두 번").hasSize(1 + SecFilingClient.TRANSIENT_RETRIES);

        statusOf.remove(indexPath(MISSING));
        SecFilingSupplement.Outcome recovered = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(recovered.supplementedVintages()).containsExactly(MISSING_FILED);
        assertThat(recovered.unconfirmedVintages()).isEmpty();
        assertThat(storedStatus(MISSING)).isEqualTo("OK");
    }

    @Test
    @DisplayName("보충한 공시의 판은 이력과 무관하게 매번 연다 — 첫 성공도 저장분도 같다(순서 무관)")
    void supplementedVintageOpensEveryRunWithoutHistory() throws Exception {
        SecFilingSupplement.Outcome first = supplement.supplement(CIK, companyFacts(), FROM, "t");
        SecFilingSupplement.Outcome second = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(first.supplementedVintages()).containsExactly(MISSING_FILED);
        assertThat(second.supplementedVintages()).containsExactly(MISSING_FILED);
    }

    @Test
    @DisplayName("폴더에 XBRL 산출물이 없으면 실패가 아니다 — 원장의 「XBRL 없음」으로 남기고 날짜를 안 지킨다")
    void noXbrlArtifactsIsALedgerFactNotAFailure() throws Exception {
        bodyOf.put(indexPath(MISSING), index("0000001800-26-000077.txt", "abt-6k.htm", "FilingSummary.xml"));

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.failed()).isEmpty();
        assertThat(outcome.unconfirmedVintages()).isEmpty();
        assertThat(outcome.supplementedVintages()).as("더한 것이 없으면 열 것이 없다").isEmpty();
        assertThat(storedStatus(MISSING)).isEqualTo("NO_XBRL");
    }

    @Test
    @DisplayName("스키마·링크베이스는 있는데 인스턴스가 없으면 해석 실패다 — XBRL은 있는데 못 고른 것이다")
    void artifactsWithoutAnInstanceIsUnreadable() throws Exception {
        bodyOf.put(indexPath(MISSING), index("abt-20260630.xsd", "abt-20260630_lab.xml", "FilingSummary.xml"));

        supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(failureKind(MISSING)).isEqualTo(SecFilingSupplement.UNREADABLE);
    }

    @Test
    @DisplayName("실패였다가 companyfacts가 그 공시를 실으면 COVERED로 남기고 매 실행 그 판을 연다 — 한 번만 열면 사라질 수 있다")
    void failedFilingLaterCoveredByCompanyFactsStaysAndReopens() throws Exception {
        statusOf.put(indexPath(MISSING), 503);
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(storedStatus(MISSING)).isEqualTo("FAILED");

        SecFilingSupplement.Outcome first = supplement.supplement(CIK, companyFactsCovering(MISSING), FROM, "t");
        SecFilingSupplement.Outcome second = supplement.supplement(CIK, companyFactsCovering(MISSING), FROM, "t");

        assertThat(first.supplementedVintages()).containsExactly(MISSING_FILED);
        assertThat(second.supplementedVintages()).as("다음 실행도 연다").containsExactly(MISSING_FILED);
        assertThat(storedStatus(MISSING)).isEqualTo("COVERED");
    }

    @Test
    @DisplayName("받은 공시를 companyfacts가 나중에 실어도 COVERED로 남기고 매 실행 그 판을 연다 — 실패였던 공시와 같게")
    void receivedFilingLaterCoveredByCompanyFactsAlsoReopens() throws Exception {
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(storedStatus(MISSING)).isEqualTo("OK");

        SecFilingSupplement.Outcome first = supplement.supplement(CIK, companyFactsCovering(MISSING), FROM, "t");
        SecFilingSupplement.Outcome second = supplement.supplement(CIK, companyFactsCovering(MISSING), FROM, "t");

        assertThat(first.supplementedVintages()).containsExactly(MISSING_FILED);
        assertThat(second.supplementedVintages()).containsExactly(MISSING_FILED);
        assertThat(storedStatus(MISSING)).isEqualTo("COVERED");
    }

    @Test
    @DisplayName("이 실행이 다시 본 해석 실패 행은 이 실행의 미해결이다 — 저장 없이 건너뛰어도 본 것으로 찍는다")
    void unreadableRowSeenAgainCountsInThatRun() throws Exception {
        SecFilingSupplementStore store = new SecFilingSupplementStore(jdbcTemplate);
        bodyOf.put(indexPath(MISSING), index("a_htm.xml", "b.xml", "FilingSummary.xml"));
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        java.time.Instant second = store.now();

        supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(store.tallySince(second).unresolved()).as("둘째 실행은 저장 없이 건너뛴다 — 본 것으로 찍혀야 센다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("미해결은 실패와 옛 판을 실제로 쓴 행뿐이다 — 판이 낮아도 안 쓰이는 행(COVERED · 대상 밖)은 안 센다")
    void unresolvedCountsStaleOnlyWhenUsed() throws Exception {
        SecFilingSupplementStore store = new SecFilingSupplementStore(jdbcTemplate);
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        // 판을 올린 뒤의 모양 — 저장 행이 전부 옛 판이다.
        jdbcTemplate.update("UPDATE sec_filing_supplement SET reader_version = 0 WHERE cik = ?", CIK);
        jdbcTemplate.update("""
                INSERT INTO sec_filing_supplement (accession, cik, form, filed, reader_version, status, facts)
                VALUES ('0000001800-20-000002', ?, '10-Q', '2020-05-01', 0, 'COVERED', NULL),
                       ('0000001800-20-000003', ?, '10-Q', '2020-08-01', 0, 'OK', '[]'::jsonb)
                """, CIK, CIK);
        assertThat(store.tallySince(store.now()).allUnresolved()).as("아직 아무도 옛 판을 안 썼다").isZero();

        statusOf.put(instancePath(MISSING, "abt-20260630_htm.xml"), 503);
        java.time.Instant since = store.now();
        SecFilingSupplement.Outcome stale = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(stale.staleReused()).isEqualTo(1);
        assertThat(store.tallySince(since).unresolved()).isEqualTo(1);
        assertThat(store.tallySince(since).allUnresolved()).as("COVERED와 대상 밖 OK는 판이 낮아도 안 센다").isEqualTo(1);

        statusOf.remove(instancePath(MISSING, "abt-20260630_htm.xml"));
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(store.tallySince(store.now()).allUnresolved()).as("다시 받으면 풀린다").isZero();
    }

    @Test
    @DisplayName("공시 목록을 못 받은 실행이 옛 판 저장분을 쓰면 미해결이다 — 다시 받을지조차 못 정했다")
    void listFailureUsingAnOldVersionIsUnresolved() throws Exception {
        SecFilingSupplementStore store = new SecFilingSupplementStore(jdbcTemplate);
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        jdbcTemplate.update("UPDATE sec_filing_supplement SET reader_version = 0 WHERE cik = ?", CIK);
        statusOf.put(submissionsPath(), 500);
        java.time.Instant since = store.now();

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.vintagesUncertain()).isTrue();
        assertThat(outcome.staleReused()).isEqualTo(1);
        assertThat(store.tallySince(since).unresolved()).isEqualTo(1);
    }

    @Test
    @DisplayName("실행 기록의 미해결은 이 실행이 본 행만 센다 — 기본 FR이 안 도는 종목의 실패 행은 표 전체 수에만 든다")
    void unresolvedCountsOnlyRowsThisRunSaw() throws Exception {
        SecFilingSupplementStore store = new SecFilingSupplementStore(jdbcTemplate);
        jdbcTemplate.update("""
                INSERT INTO sec_filing_supplement (accession, cik, form, filed, reader_version, status, failure_kind,
                    failure_message, fetched_at, last_seen_at)
                VALUES ('0000001800-20-000001', ?, '10-Q', '2020-05-01', 1, 'FAILED', 'TRANSIENT', 'x',
                    now() - interval '40 days', now() - interval '40 days')
                """, CIK);
        java.time.Instant since = store.now();
        statusOf.put(indexPath(MISSING), 404);

        supplement.supplement(CIK, companyFacts(), FROM, "t");

        SecFilingSupplementStore.Tally tally = store.tallySince(since);
        assertThat(tally.failed()).isEqualTo(1);
        assertThat(tally.unresolved()).as("이 실행이 본 실패 하나").isEqualTo(1);
        assertThat(tally.allUnresolved()).as("표 전체 — 이 실행이 안 본 옛 실패 행까지").isEqualTo(2);
    }

    @Test
    @DisplayName("다른 문서를 고르면 그 공시의 실패다 — 저장하지 않고 그 날짜를 지킨다")
    void anotherDocumentIsAFailureOfThatFiling() throws Exception {
        bodyOf.put(submissionsPath(), submissions(List.of(
                filing(MISSING, "10-K", MISSING_FILED.toString(), 1)), List.of()));

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFacts(), FROM, "t");

        assertThat(outcome.failed()).containsExactly(MISSING);
        assertThat(failureKind(MISSING)).isEqualTo(SecFilingSupplement.UNREADABLE);
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
        assertThat(outcome.staleReused()).as("지금 판의 저장분은 옛 판이 아니다").isZero();
        assertThat(new SecFilingSupplementStore(jdbcTemplate).tallySince(java.time.Instant.EPOCH).allUnresolved())
                .isZero();
    }

    @Test
    @DisplayName("공시 목록을 못 받은 실행도 companyfacts가 덮은 공시를 COVERED로 바꾸고 그 판을 연다 — 정리는 목록이 필요 없다")
    void listFailureStillSettlesCoveredFilings() throws Exception {
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        statusOf.put(submissionsPath(), 500);

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK, companyFactsCovering(MISSING), FROM, "t");

        assertThat(outcome.vintagesUncertain()).isTrue();
        assertThat(outcome.supplementedVintages()).contains(MISSING_FILED);
        assertThat(storedStatus(MISSING)).isEqualTo("COVERED");
    }

    @Test
    @DisplayName("덮인 공시는 companyfacts가 붙인 filed 날짜도 연다 — 하루 늦게 매기는 공시가 있다")
    void coveredFilingOpensCompanyFactsFiledDateToo() throws Exception {
        supplement.supplement(CIK, companyFacts(), FROM, "t");

        SecFilingSupplement.Outcome outcome = supplement.supplement(CIK,
                companyFactsCovering(MISSING, MISSING_FILED.plusDays(1)), FROM, "t");

        assertThat(outcome.supplementedVintages()).containsExactlyInAnyOrder(MISSING_FILED, MISSING_FILED.plusDays(1));
    }

    @Test
    @DisplayName("옛 판을 쓴 행이 COVERED가 되거나 XBRL 없음으로 다시 받아지면 미해결에서 빠진다")
    void staleMarkClearsWhenCoveredOrRefetchedAsNoXbrl() throws Exception {
        SecFilingSupplementStore store = new SecFilingSupplementStore(jdbcTemplate);
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        jdbcTemplate.update("UPDATE sec_filing_supplement SET reader_version = 0 WHERE cik = ?", CIK);
        statusOf.put(instancePath(MISSING, "abt-20260630_htm.xml"), 503);
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(store.tallySince(java.time.Instant.EPOCH).allUnresolved()).isEqualTo(1);

        supplement.supplement(CIK, companyFactsCovering(MISSING), FROM, "t");
        assertThat(store.tallySince(java.time.Instant.EPOCH).allUnresolved()).as("COVERED가 지운다").isZero();

        // 다시 대상이 되고(companyfacts가 다시 뺌) 옛 판 → 받기 실패 → XBRL 없음으로 다시 받음.
        jdbcTemplate.update("""
                UPDATE sec_filing_supplement SET status = 'OK', facts = '[]'::jsonb, reader_version = 0
                WHERE cik = ? AND accession = ?""", CIK, MISSING);
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(store.tallySince(java.time.Instant.EPOCH).allUnresolved()).isEqualTo(1);
        statusOf.remove(instancePath(MISSING, "abt-20260630_htm.xml"));
        bodyOf.put(indexPath(MISSING), index("0000001800-26-000077.txt", "abt-6k.htm", "FilingSummary.xml"));
        supplement.supplement(CIK, companyFacts(), FROM, "t");
        assertThat(storedStatus(MISSING)).isEqualTo("NO_XBRL");
        assertThat(store.tallySince(java.time.Instant.EPOCH).allUnresolved()).as("XBRL 없음 저장이 지운다").isZero();
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

    /** companyfacts가 그 공시의 us-gaap까지 싣는 모양 — 나중에 SEC가 채운 경우. */
    private ObjectNode companyFactsCovering(String accession) throws Exception {
        return companyFactsCovering(accession, MISSING_FILED);
    }

    private ObjectNode companyFactsCovering(String accession, LocalDate filed) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"us-gaap":{"Revenues":{"units":{"USD":[
                  {"start":"2026-01-01","end":"2026-03-31","val":10000000000,"accn":"%s","fy":2026,"fp":"Q1","form":"10-Q","filed":"2026-05-01"},
                  {"start":"2026-04-01","end":"2026-06-30","val":11138000000,"accn":"%s","fy":2026,"fp":"Q2","form":"10-Q","filed":"%s"}]}}}}"""
                .formatted(COVERED, accession, filed));
    }

    private String failureKind(String accession) {
        return jdbcTemplate.queryForObject("SELECT failure_kind FROM sec_filing_supplement WHERE accession = ?",
                String.class, accession);
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
