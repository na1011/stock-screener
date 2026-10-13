package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.FilingLedgerStore;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.FilingListSource;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FilingListingJob}(ADR-0176) — 커서를 이어받고, 다시 돌아도 장부가 안 늘고, 한 시장의 실패가 다른 시장을 안 막고,
 * 커서가 늦으면 사유와 함께 알린다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다 — 잡이 실행 행과 하루치 장부를 자기 트랜잭션으로 커밋한다(ADR-0007).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class FilingListingJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final Map<String, LocalDate> FIRST = Map.of(
            "US", LocalDate.of(2026, 10, 8), "KR", LocalDate.of(2026, 10, 12));

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FilingLedgerStore store;
    @Autowired
    private StockTaskExecutor executor;
    @Autowired
    private BatchExecutionRecorder recorder;
    @Autowired
    private ApplicationContext context;

    private final List<AlertEvent> alerts = new ArrayList<>();
    private BatchExecutions executions;
    private long usStock;
    private long krStock;

    @BeforeEach
    void setUp() {
        cleanUp();
        AlertPort recording = alerts::add;
        executions = new BatchExecutions(executor, recorder, recording, context);
        usStock = stock("US", "ZZFL");
        krStock = stock("KOSPI", "990001");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM filing_consumption WHERE stock_id IN (SELECT id FROM stock WHERE ticker IN ('ZZFL', '990001'))");
        jdbc.update("DELETE FROM filing_listing_day");
        jdbc.update("DELETE FROM batch_execution_phase WHERE execution_id IN "
                + "(SELECT id FROM batch_execution WHERE job_name = ?)", FilingListingJob.JOB_NAME);
        jdbc.update("DELETE FROM batch_execution WHERE job_name = ?", FilingListingJob.JOB_NAME);
        jdbc.update("DELETE FROM stock WHERE ticker IN ('ZZFL', '990001')");
        alerts.clear();
    }

    @Test
    @DisplayName("첫 실행은 설정의 첫 날부터, 다음 실행은 닫은 마지막 날 다음부터 — 같은 줄은 장부에 다시 안 들어간다")
    void resumesFromTheCursorAndIsIdempotent() {
        List<LocalDate> cursors = new ArrayList<>();
        FilingListSource us = source("US", (cursor) -> {
            cursors.add(cursor);
            List<FilingListSource.ListedDay> days = new ArrayList<>();
            for (LocalDate d = cursor.plusDays(1); d.isBefore(TODAY); d = d.plusDays(1)) {
                days.add(new FilingListSource.ListedDay(d, FilingListSource.DayOutcome.LISTED, 1,
                        List.of(usFiling("0000000001-26-" + d.getDayOfMonth(), d))));
            }
            return new FilingListSource.FilingListing(days, List.of(), null);
        });
        FilingListSource kr = source("KR", cursor -> new FilingListSource.FilingListing(List.of(),
                List.of(krFiling("20261013000001", TODAY)), null));
        FilingListingJob job = new FilingListingJob(List.of(us, kr), store, executions, FIRST);

        assertThat(job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isEqualTo(BatchStatus.SUCCESS);
        assertThat(job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).isEqualTo(BatchStatus.SUCCESS);

        assertThat(cursors).containsExactly(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 12));
        assertThat(count("SELECT count(*) FROM filing_consumption WHERE stock_id = ?", usStock))
                .as("10-08 ~ 10-12 다섯 날 · 둘째 실행은 닫을 날이 없다").isEqualTo(5);
        assertThat(count("SELECT count(*) FROM filing_consumption WHERE stock_id = ?", krStock))
                .as("국내 오늘 줄 — 두 번 받아도 한 행").isEqualTo(1);
        assertThat(store.cursor("KR")).as("국내 오늘은 안 닫는다 — 커서 없음").isEmpty();
        assertThat(store.cursor("US")).contains(LocalDate.of(2026, 10, 12));
        assertThat(jdbc.queryForObject("SELECT written_row_count FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1",
                Long.class, FilingListingJob.JOB_NAME)).as("둘째 실행이 새로 쓴 장부 행").isZero();
    }

    @Test
    @DisplayName("소스가 없는 시장은 실패로 세고 다른 시장은 돈다 — 키 없는 시장을 「목록 0건」으로 접지 않는다")
    void missingSourceIsAFailureNotAnEmptyList() {
        FilingListSource kr = source("KR", cursor -> new FilingListSource.FilingListing(
                List.of(new FilingListSource.ListedDay(cursor.plusDays(1), FilingListSource.DayOutcome.EMPTY, 0, List.of())),
                List.of(), null));
        FilingListingJob job = new FilingListingJob(List.of(kr), store, executions, FIRST);

        BatchStatus status = job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(status).isEqualTo(BatchStatus.PARTIAL_SUCCESS);
        assertThat(store.cursor("KR")).contains(LocalDate.of(2026, 10, 12));
        assertThat(jdbc.queryForObject("SELECT note FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1",
                String.class, FilingListingJob.JOB_NAME)).contains("US: 소스 없음");
    }

    @Test
    @DisplayName("받기가 실패해 커서가 평일 2일 넘게 늦으면 마지막 실패 사유와 함께 알린다")
    void alertsWhenTheCursorLags() {
        DataSourceException forbidden = new DataSourceException(DataSourceException.Kind.RATE_LIMITED, "sec-edgar",
                "HTTP 403. daily-index 2026 QTR4");
        FilingListSource us = source("US", cursor -> new FilingListSource.FilingListing(List.of(), List.of(), forbidden));
        FilingListSource kr = source("KR", cursor -> new FilingListSource.FilingListing(List.of(), List.of(), null));
        FilingListingJob job = new FilingListingJob(List.of(us, kr), store, executions, FIRST);

        job.run(TODAY, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        // US 첫 날 10-08 → 커서 10-07, 그 뒤 평일 10-08 · 10-09 · 10-12 = 3일 > 2. KR 커서 10-11 → 평일 10-12 하나.
        assertThat(alerts).anyMatch(e -> e.title().contains("공시 목록 커서가 늦다")
                && e.body().contains("US 커서 2026-10-07") && e.body().contains("HTTP 403")
                && !e.body().contains("KR 커서"));
    }

    @Test
    @DisplayName("평일 수 — 양 끝을 빼고 주말을 뺀다")
    void countsWeekdaysBetween() {
        assertThat(FilingListingJob.weekdaysBetween(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 13)))
                .as("금 → 화: 월 하나").isEqualTo(1);
        assertThat(FilingListingJob.weekdaysBetween(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13))).isZero();
    }

    private FilingListSource.ListedFiling usFiling(String accession, LocalDate day) {
        return new FilingListSource.ListedFiling(accession, usStock, 1L, null, "10-Q", null, day, null, null, null);
    }

    private FilingListSource.ListedFiling krFiling(String rceptNo, LocalDate day) {
        return new FilingListSource.ListedFiling(rceptNo, krStock, null, "00000001", "A003", "분기보고서 (2026.09)", day,
                2026, "11014", null);
    }

    private static FilingListSource source(String market, Function<LocalDate, FilingListSource.FilingListing> list) {
        return new FilingListSource() {
            @Override
            public String market() {
                return market;
            }

            @Override
            public FilingListing list(LocalDate cursor, LocalDate todayKst, Map<String, ListedStock> stocks) {
                return list.apply(cursor);
            }
        };
    }

    private long stock(String market, String ticker) {
        return jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id",
                Long.class, market, ticker, ticker);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
