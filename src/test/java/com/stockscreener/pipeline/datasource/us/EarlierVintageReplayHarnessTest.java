package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.indicator.StoredIndicatorInvalidationPort;
import com.stockscreener.pipeline.collector.FinancialWriter;
import com.stockscreener.pipeline.datasource.RawFinancialReport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팩트 캐시를 <b>사본 DB</b>의 {@link FinancialWriter}에 이른 판 삽입을 켠 채 재생한다(ADR-0161 드라이런).
 *
 * <p>{@link SecEdgarFactsSweepTest}는 어댑터 출력만 찍어 <b>저장 계층의 동작</b>(회수·재번호·무효화·
 * 같은 호출 안 번호 충돌)을 못 본다. 이 하네스는 운영 {@code financial-refill}과 같은 입력
 * ({@code SecEdgarFacts.build(facts, fromFiscalYear)} 전량)을 같은 5-인자 {@code writeDetailed}에
 * 종목마다 트랜잭션 하나로 넣는다 — {@code StockTaskRunner}의 {@code REQUIRES_NEW} 단위와 같다.
 *
 * <p><b>MUST: 사본에서만 돈다.</b> 첫 쓰기 전에 연결의 catalog가 {@code stock_screener_q4v}로 시작하는지
 * 단언한다 — 운영 DB에 돌면 재번호·무효화가 되돌릴 수 없이 남는다.
 *
 * <pre>
 * Q4V_REPLAY_DB=stock_screener_q4v_r1 Q4V_REPLAY_CACHE=$PWD/.factcache \
 * Q4V_REPLAY_TARGETS=targets.tsv Q4V_REPLAY_OUT=replay.tsv \
 * Q4V_REPLAY_FROM=2018 Q4V_REPLAY_INSERT_EARLIER=false Q4V_REPLAY_SUPPLEMENT_DIR=off \
 *   ./gradlew test --tests "*EarlierVintageReplayHarnessTest*" -PskipFrontend
 * </pre>
 *
 * <p><b>공시 보충(ADR-0173 · RS1)</b>: {@code Q4V_REPLAY_SUPPLEMENT_DIR}를 주면 같은 날 모은 보충 원자료로
 * {@link SecFilingSupplement}를 태우고 그 판 보호를 Writer에 넘긴다({@link SupplementReplay}) — <b>그 변수는 필수다</b>(경로 또는
 * {@code off}). {@code Q4V_REPLAY_SUPPLEMENT_STORE=db}면 사본의 저장 표를 원장으로 쓴다(V64가 그 사본에 있어야 한다). 그 캐시의 companyfacts는
 * {@code <dir>/companyfacts}이므로 {@code Q4V_REPLAY_CACHE}도 거기를 준다.
 *
 * <p><b>{@code Q4V_REPLAY_FROM}·{@code Q4V_REPLAY_INSERT_EARLIER}도 필수다</b> — 정기 FR과 같게 돌리려면 {@code 2018}(asOf − 8 ·
 * {@code FinancialRefillJob.DEFAULT_YEARS_BACK})·{@code false}, 관문 재적재와 같게 돌리려면 {@code 2015}·{@code true}다. 예전
 * 기본값(2015 · 켬)은 관문 쪽이라, 정기 경로를 재려던 실행이 조용히 다른 답을 냈다(2026-10-06 v6 반증 — 첫 손 FR의 사본 재생).
 *
 * <p>{@code Q4V_REPLAY_TARGETS}는 {@code stock_id<TAB>ticker} 줄이다(머리줄 없음). 캐시는 09-15 전후 단면이라
 * 그 뒤 공시된 DB 판은 회수 후보로 잡힌다 — 재생 <b>전에</b> 그 수를 종목마다 세어 함께 찍는다.
 */
@EnabledIfEnvironmentVariable(named = "Q4V_REPLAY_DB", matches = "stock_screener_q4v.*")
class EarlierVintageReplayHarnessTest {

    private record Vintage(int fiscalYear, String fiscalPeriod, LocalDate publishedAt) {
    }

    private record Label(int fiscalYear, String fiscalPeriod) {
    }

    @Test
    void replay() throws Exception {
        String db = System.getenv("Q4V_REPLAY_DB");
        Path cache = Path.of(System.getenv("Q4V_REPLAY_CACHE"));
        Path targets = Path.of(System.getenv("Q4V_REPLAY_TARGETS"));
        Path out = Path.of(Optional.ofNullable(System.getenv("Q4V_REPLAY_OUT")).orElse("build/q4v-replay.tsv"));
        int from = Integer.parseInt(required("Q4V_REPLAY_FROM"));
        String user = Optional.ofNullable(System.getenv("DB_USERNAME")).orElse(System.getProperty("user.name"));
        String earlier = required("Q4V_REPLAY_INSERT_EARLIER");
        if (!earlier.equals("true") && !earlier.equals("false")) {
            throw new IllegalStateException("Q4V_REPLAY_INSERT_EARLIER는 true 또는 false다: " + earlier);
        }
        boolean insertEarlier = Boolean.parseBoolean(earlier);
        Optional<Path> supplementDir = SupplementReplay.requireSetting("Q4V_REPLAY_SUPPLEMENT_DIR");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:5432/" + db, user, "");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).startsWith("stock_screener_q4v").isEqualTo(db);
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // db면 사본의 sec_filing_supplement(V64)를 원장으로 쓴다 — 두 번째 재생이 저장분 경로(jsonb → 고르기 → 합치기)를
        // 탄다. 문장마다 커밋되므로 Writer와의 트랜잭션 맞물림은 SecFilingSupplementWriteContractTest(RS2)가 묻는다.
        boolean dbLedger = "db".equals(System.getenv("Q4V_REPLAY_SUPPLEMENT_STORE"));
        Optional<SupplementReplay> supplement = supplementDir.map(dir -> dbLedger
                ? SupplementReplay.start(dir, new SecFilingSupplementStore(jdbc))
                : SupplementReplay.start(dir));
        FinancialWriter writer = new FinancialWriter(jdbc, new StoredIndicatorInvalidationPort(jdbc));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        Map<String, Long> before = globalCounts(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = Files.readAllLines(targets, StandardCharsets.UTF_8);
        List<String> failures = new ArrayList<>();
        long[] sums = new long[10];
        // 첫 손 FR의 중단 조건이 보는 수 — 보충 실패 공시 · 목록을 못 받은 종목 · 회수를 멈춘 종목 · 회수된 행.
        long supplementFailed = 0;
        long listFailed = 0;
        long retractionStopped = 0;
        long retracted = 0;

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write(String.join("\t", "stock_id", "ticker", "reports", "pre_retract", "pre_earlier", "pre_newer",
                    "rows_before", "rows_after", "changed", "inserted", "filled", "revised", "deferred",
                    "corrected", "inserted_earlier"));
            w.newLine();
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split("\t");
                long stockId = Long.parseLong(cols[0]);
                String ticker = cols[1];
                List<RawFinancialReport> reports;
                FinancialWriter.VintageGuard guard = FinancialWriter.VintageGuard.NONE;
                try {
                    JsonNode root = mapper.readTree(Files.readString(cache.resolve(ticker + ".json")));
                    JsonNode facts = root.path("facts");
                    if (supplement.isPresent()) {
                        SecFilingSupplement.Outcome outcome = supplement.get().supplement()
                                .supplement(root.path("cik").asLong(), (ObjectNode) facts, from, ticker);
                        guard = new FinancialWriter.VintageGuard(outcome.unconfirmedVintages(),
                                outcome.supplementedVintages(), outcome.vintagesUncertain());
                        supplementFailed += outcome.failed().size();
                        listFailed += outcome.vintagesUncertain() ? 1 : 0;
                    }
                    SecEdgarFacts.Result result = SecEdgarFacts.build(facts, from);
                    reports = result.reports();
                    // 운영(SecEdgarFinancialAdapter → FinancialCollector)과 같은 가드 — 통화로 안 담은 판도 지킨다.
                    guard = new FinancialWriter.VintageGuard(guard.unconfirmed(), guard.supplemented(),
                            guard.uncertain(), result.withheld());
                } catch (Exception e) {
                    failures.add(ticker + " 읽기=" + e.getClass().getSimpleName() + ":" + e.getMessage());
                    continue;
                }

                // 재생 전 대조: 회수 후보(DB에만 있는 판) · 이른 판 후보 · 새 판 후보.
                List<Vintage> stored = jdbc.query(
                        "SELECT fiscal_year, fiscal_period, published_at FROM financial_statement WHERE stock_id = ?",
                        (rs, n) -> new Vintage(rs.getInt(1), rs.getString(2), rs.getDate(3).toLocalDate()),
                        stockId);
                Set<Vintage> storedSet = new HashSet<>(stored);
                Map<Label, LocalDate> latestStored = new HashMap<>();
                for (Vintage v : stored) {
                    latestStored.merge(new Label(v.fiscalYear(), v.fiscalPeriod()), v.publishedAt(),
                            (a, b) -> a.isAfter(b) ? a : b);
                }
                Set<Vintage> sourceSet = new HashSet<>();
                Set<Label> sourceLabels = new HashSet<>();
                for (RawFinancialReport r : reports) {
                    sourceSet.add(new Vintage(r.fiscalYear(), r.fiscalPeriod(), r.publishedAt()));
                    sourceLabels.add(new Label(r.fiscalYear(), r.fiscalPeriod()));
                }
                long preRetract = stored.stream()
                        .filter(v -> sourceLabels.contains(new Label(v.fiscalYear(), v.fiscalPeriod())))
                        .filter(v -> !sourceSet.contains(v))
                        .count();
                long preEarlier = sourceSet.stream()
                        .filter(v -> !storedSet.contains(v))
                        .filter(v -> {
                            LocalDate latest = latestStored.get(new Label(v.fiscalYear(), v.fiscalPeriod()));
                            return latest != null && v.publishedAt().isBefore(latest);
                        })
                        .count();
                long preNewer = sourceSet.stream()
                        .filter(v -> !storedSet.contains(v))
                        .filter(v -> {
                            LocalDate latest = latestStored.get(new Label(v.fiscalYear(), v.fiscalPeriod()));
                            return latest == null || v.publishedAt().isAfter(latest);
                        })
                        .count();

                long rowsBefore = rowCount(jdbc, stockId);
                FinancialWriter.Outcome outcome;
                try {
                    List<RawFinancialReport> all = reports;
                    FinancialWriter.VintageGuard vintageGuard = guard;
                    outcome = tx.execute(status -> writer.writeDetailed(
                            stockId, all, FinancialWriter.WriteMode.FILL_MISSING, true, insertEarlier, vintageGuard));
                } catch (RuntimeException e) {
                    failures.add(ticker + " 쓰기=" + e.getClass().getSimpleName() + ":" + e.getMessage());
                    continue;
                }
                long rowsAfter = rowCount(jdbc, stockId);

                long[] row = {reports.size(), preRetract, preEarlier, preNewer, rowsBefore, rowsAfter,
                        outcome.changed(), outcome.inserted(), outcome.filled(), outcome.revised()};
                w.write(stockId + "\t" + ticker + "\t" + row[0] + "\t" + row[1] + "\t" + row[2] + "\t" + row[3]
                        + "\t" + row[4] + "\t" + row[5] + "\t" + row[6] + "\t" + row[7] + "\t" + row[8] + "\t"
                        + row[9] + "\t" + outcome.deferredRevisions() + "\t" + outcome.corrected() + "\t"
                        + outcome.insertedEarlier());
                w.newLine();
                sums[0] += preRetract;
                sums[1] += preEarlier;
                sums[2] += preNewer;
                sums[3] += rowsAfter - rowsBefore;
                sums[4] += outcome.inserted();
                sums[5] += outcome.filled();
                sums[6] += outcome.revised();
                sums[7] += outcome.deferredRevisions();
                sums[8] += outcome.corrected();
                sums[9] += outcome.insertedEarlier();
                retractionStopped += outcome.retractionStopped() ? 1 : 0;
                // 새로 생기는 행은 신규 · 정정 · 이른 판뿐이다 — 그 합에서 행 증감을 빼면 회수로 지운 행이다.
                retracted += outcome.inserted() + outcome.revised() + outcome.insertedEarlier() - (rowsAfter - rowsBefore);
            }
        }

        Map<String, Long> after = globalCounts(jdbc);
        System.out.println("Q4V_REPLAY db=" + db + " 대상=" + lines.size() + " 실패=" + failures.size()
                + " 회수후보=" + sums[0] + " 이른판후보=" + sums[1] + " 새판후보=" + sums[2]
                + " 행증감=" + sums[3] + " 신규=" + sums[4] + " 채움=" + sums[5] + " 정정=" + sums[6]
                + " 정정보류=" + sums[7] + " 교정=" + sums[8] + " 이른판=" + sums[9]
                + " 회수=" + retracted + " 보충실패=" + supplementFailed + " 목록실패종목=" + listFailed
                + " 회수정지=" + retractionStopped + "종목"
                + " 전=" + before + " 후=" + after + " out=" + out.toAbsolutePath());
        failures.forEach(f -> System.out.println("Q4V_REPLAY 실패 " + f));
        supplement.ifPresent(r -> System.out.println("Q4V_REPLAY 보충 원자료 없음(404)=" + r.missing()));
        supplement.ifPresent(SupplementReplay::close);
        supplement.ifPresent(r -> assertThat(r.missing()).as("보충 원자료 없음(404) — 수집을 다시 한다").isZero());
        assertThat(failures).isEmpty();
    }

    private static long rowCount(JdbcTemplate jdbc, long stockId) {
        return jdbc.queryForObject("SELECT count(*) FROM financial_statement WHERE stock_id = ?", Long.class, stockId);
    }

    private static Map<String, Long> globalCounts(JdbcTemplate jdbc) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT (s.market_code IN ('KOSPI','KOSDAQ')) AS kr, count(*) AS rows_,
                       count(*) FILTER (WHERE f.is_latest) AS latest, sum(f.revision_no) AS rev_sum
                FROM financial_statement f JOIN stock s ON s.id = f.stock_id GROUP BY 1
                """, rs -> {
            String market = rs.getBoolean("kr") ? "KR" : "US";
            counts.put(market + "행", rs.getLong("rows_"));
            counts.put(market + "최신", rs.getLong("latest"));
            counts.put(market + "번호합", rs.getLong("rev_sum"));
        });
        return counts;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + "가 없다 — 정기 FR과 같게: FROM=2018 · INSERT_EARLIER=false / "
                    + "관문 재적재와 같게: FROM=2015 · INSERT_EARLIER=true");
        }
        return value.trim();
    }
}
