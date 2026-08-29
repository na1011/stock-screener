package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.BarGapReader;
import com.stockscreener.pipeline.collector.MarketCalendarCollector;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.MarketCalendarDataSource;
import com.stockscreener.pipeline.datasource.MarketSession;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketCalendarJob}이 <b>조용히 성공하지 않는가</b>를 잠근다.
 *
 * <p>이 잡의 실패는 전부 무증상이다 — 대상이 0건이어도, 창이 미래여도, 소스가 세션을 거둬가도
 * 값은 그럴듯하고 잡은 초록이다. 그래서 여기서 잡는다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다. 잡이 실행 행을 자기 트랜잭션으로 커밋하므로
 * 롤백 격리가 성립하지 않는다(ADR-0007).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, MarketCalendarJobTest.FakeCalendarConfig.class,
        MarketCalendarJobTest.RecordingAlertConfig.class})
class MarketCalendarJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2024, 1, 10);
    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);

    @Autowired
    private MarketCalendarJob job;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeCalendarSource source;

    @Autowired
    private RecordingAlertPort alerts;

    @Autowired
    private MarketCalendarCollector collector;

    @Autowired
    private DataSourceRegistry registry;

    @Autowired
    private BarGapReader gapReader;

    @Autowired
    private BatchExecutions batchExecutions;

    @BeforeEach
    void reset() {
        cleanUp();
        source.reset();
        alerts.events.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_phase WHERE execution_id IN "
                + "(SELECT id FROM batch_execution WHERE job_name = ?)", MarketCalendarJob.JOB_NAME);
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ?",
                MarketCalendarJob.JOB_NAME);
        jdbcTemplate.update("DELETE FROM market_calendar");
    }

    @Test
    @DisplayName("두 번째 실행이 아무것도 바꾸지 않는다 — 가드가 없으면 매번 전량을 신고한다")
    void secondRunChangesNothing() {
        source.sessions = List.of("2024-01-02", "2024-01-03", "2024-01-04");

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);
        long firstRows = writtenRows();
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);
        long secondRows = writtenRows();

        assertThat(firstRows).as("거래소 둘 × 3일").isEqualTo(6);
        assertThat(secondRows).as("같은 답을 다시 받으면 0이고 그것이 멱등의 증거다").isZero();
        assertThat(rowCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("소스가 거둬간 세션은 지운다 — 안 지우면 표가 append-only라 휴일이 개장일로 굳는다")
    void removesWithdrawnSessions() {
        source.sessions = List.of("2024-01-02", "2024-01-03", "2024-01-04");
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);
        assertThat(rowCount()).isEqualTo(6);

        source.sessions = List.of("2024-01-02", "2024-01-04");
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);

        assertThat(rowCount()).as("거래소마다 하루씩 사라져야 한다").isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_calendar WHERE session_date = '2024-01-03'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("창 밖은 안 건드린다 — 좁게 물었다고 이전 적재가 사라지면 창이 조용히 좁아진다")
    void doesNotTouchRowsOutsideTheWindow() {
        source.sessions = List.of("2024-01-02", "2024-01-03");
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);

        // 더 좁은 창으로 다시 돈다. 소스는 그 창 안의 하루만 준다.
        source.sessions = List.of("2024-01-03");
        job.run(LocalDate.of(2024, 1, 3), BatchExecutionRecorder.TriggeredBy.MANUAL,
                LocalDate.of(2024, 1, 3));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_calendar WHERE session_date = '2024-01-02'", Long.class))
                .as("창 밖의 01-02는 그대로 있어야 한다")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("대상일이 미래면 실패한다 — 소스는 먼 미래의 휴장일을 모르면서 평일을 세션으로 준다")
    void rejectsFutureAsOf() {
        source.sessions = List.of("2024-01-02");

        BatchStatus status = job.run(LocalDate.now().plusDays(1),
                BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);

        assertThat(status).isEqualTo(BatchStatus.FAILED);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("창의 시작이 대상일보다 늦으면 실패한다")
    void rejectsInvertedWindow() {
        source.sessions = List.of("2024-01-02");

        assertThat(job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, AS_OF.plusDays(1)))
                .isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("한 거래소가 실패해도 다른 거래소는 받고, 실패는 알림으로 나간다")
    void oneExchangeFailureDoesNotStopTheOther() {
        source.sessions = List.of("2024-01-02", "2024-01-03");
        source.failingExchange = "NASDAQ";

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_calendar WHERE exchange_code = 'NYSE'", Long.class))
                .isEqualTo(2L);
        assertThat(alerts.events).extracting(AlertEvent::title)
                .anyMatch(t -> t.contains("세션 달력 수집 실패"));
    }

    @Test
    @DisplayName("결측이 있으면 요약이 알림으로 나간다 — 표만 채우고 아무도 안 보면 이 잡은 아무것도 답하지 않는다")
    void alertsWhenGapsExist() {
        // 미국은 일일 갱신 대상이 아니라 DailyUpdateJob의 결손 알림이 원리적으로 안 닿는다.
        // 이 알림이 이 축에서 사람에게 닿는 유일한 자동 경로다.
        source.sessions = List.of("2024-01-02", "2024-01-03", "2024-01-04");
        long stockId = usStockWithBars("GAPPY", "2024-01-02", "2024-01-04");

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);

        assertThat(alerts.events)
                .anyMatch(e -> e.title().contains("봉 결측이 있다")
                        && e.body().contains("SESSION_WITHOUT_BAR")
                        && e.body().contains("GAPPY")
                        && e.body().contains("2024-01-03"));
        jdbcTemplate.update("DELETE FROM price_daily WHERE stock_id = ?", stockId);
        jdbcTemplate.update("DELETE FROM stock WHERE id = ?", stockId);
    }

    @Test
    @DisplayName("어댑터가 하나도 없으면 0건 성공이 아니라 ERROR 알림이다")
    void emptyTargetListAlerts() {
        MarketCalendarJob withoutSource = new MarketCalendarJob(collector, gapReader,
                new DataSourceRegistry(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                batchExecutions);

        withoutSource.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, FROM);

        assertThat(alerts.events)
                .as("조용히 끝나면 달력이 영영 안 들어오는데 잡은 초록이다")
                .anyMatch(e -> e.severity() == AlertSeverity.ERROR
                        && e.title().contains("마켓 캘린더 소스가 등록되지 않았다"));
    }

    // ── 도구 ─────────────────────────────────────────────────────────────

    private long usStockWithBars(String ticker, String... dates) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, listing_exchange)
                VALUES ('US', ?, ?, 'NYSE') RETURNING id
                """, Long.class, ticker, ticker + " Inc.");
        for (String date : dates) {
            jdbcTemplate.update("""
                    INSERT INTO price_daily
                        (stock_id, trade_date, open, high, low, close, adj_close, adj_factor, volume)
                    VALUES (?, ?::date, 100, 100, 100, 100, 100, 1, 1000)
                    """, id, date);
        }
        return id == null ? 0L : id;
    }

    private long rowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM market_calendar", Long.class);
        return count == null ? 0L : count;
    }

    private long writtenRows() {
        Long rows = jdbcTemplate.queryForObject("""
                SELECT written_row_count FROM batch_execution
                 WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, Long.class, MarketCalendarJob.JOB_NAME);
        return rows == null ? 0L : rows;
    }

    /** 소스를 흉내 낸다. 실제 호출은 {@code AlpacaMarketCalendarLiveTest}가 지킨다. */
    static class FakeCalendarSource implements MarketCalendarDataSource {

        List<String> sessions = List.of();
        String failingExchange;

        void reset() {
            sessions = List.of();
            failingExchange = null;
        }

        @Override
        public Set<String> supportedExchangeCodes() {
            return Set.of("NYSE", "NASDAQ");
        }

        @Override
        public String sourceName() {
            return "fake:calendar";
        }

        @Override
        public List<MarketSession> fetchSessions(String exchangeCode, LocalDate from, LocalDate to)
                throws DataSourceException {
            if (exchangeCode.equals(failingExchange)) {
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, sourceName(),
                        "일부러 실패시킨다");
            }
            List<MarketSession> result = new ArrayList<>();
            for (String date : sessions) {
                LocalDate day = LocalDate.parse(date);
                if (day.isBefore(from) || day.isAfter(to)) {
                    continue;
                }
                result.add(new MarketSession(exchangeCode, day,
                        Instant.parse(date + "T14:30:00Z"), Instant.parse(date + "T21:00:00Z")));
            }
            return result;
        }
    }

    @TestConfiguration
    static class FakeCalendarConfig {
        @Bean
        @Primary
        FakeCalendarSource fakeCalendarSource() {
            return new FakeCalendarSource();
        }
    }

    /** 알림을 실제로 보내는 대신 모아둔다. */
    static class RecordingAlertPort implements AlertPort {
        final List<AlertEvent> events = new ArrayList<>();

        @Override
        public void send(AlertEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration
    static class RecordingAlertConfig {
        @Bean
        @Primary
        RecordingAlertPort recordingAlertPort() {
            return new RecordingAlertPort();
        }
    }
}
