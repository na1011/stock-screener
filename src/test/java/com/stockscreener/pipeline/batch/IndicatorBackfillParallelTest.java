package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.calculator.AdjCloseCalculator;
import com.stockscreener.support.TestDatabaseGuard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백필의 <b>종목 루프 팬아웃 배선</b>을 고정한다(ADR-0138).
 *
 * <p>기본 테스트 프로파일은 커넥션 풀이 2라 클램프가 워커를 1로 깎는다 — 즉 <b>다른 백필
 * 시험들은 이 배선을 한 번도 안 태운다.</b> 그래서 이 클래스만 풀과 병렬도를 올려 별도
 * 컨텍스트를 띄운다. 폭은 필요한 최소다({@code application-test.yaml}이 풀을 2로 묶어 둔 이유가
 * <i>"기본값 10이면 무관한 테스트가 too many clients로 깨진다 — 실제 14건"</i>이다).
 *
 * <p><b>MUST: 워커가 정말 둘이었는지를 단정한다.</b> 초록만으로는 클램프가 조용히 1로 깎은
 * 것과 구분되지 않는다 — 그러면 이 시험은 직렬 경로를 다시 재는 것이고, 아무것도 지키지 않는다.
 * 잡이 <b>스스로 신고하는</b> 워커 수를 로그 인자에서 꺼내 본다(그 신고가 ADR-0138의 MUST다).
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007).
 */
@SpringBootTest(properties = {
        // 워커 둘이 각자 커넥션 하나를 계산하는 내내 쥔다. RESERVED_CONNECTIONS(2)를 빼면
        // 풀 상한이 정확히 2다 — 이 값을 3으로 내리면 워커가 1이 되어 시험이 무의미해진다.
        "spring.datasource.hikari.maximum-pool-size=4",
        "pipeline.indicator-backfill.parallelism=2",
})
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class IndicatorBackfillParallelTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);
    private static final String UNIVERSE = "KOSPI200";
    private static final List<String> TICKERS =
            List.of("910001", "910002", "910003", "910004", "910005", "910006");

    /** 종목마다 다른 종가. 값이 섞이면 그 자체가 발견이다. */
    private static final Map<String, BigDecimal> CLOSES = Map.of(
            "910001", new BigDecimal("1000"), "910002", new BigDecimal("2000"),
            "910003", new BigDecimal("3000"), "910004", new BigDecimal("4000"),
            "910005", new BigDecimal("5000"), "910006", new BigDecimal("6000"));

    @Autowired
    private IndicatorBackfillJob job;

    @Autowired
    private BatchExecutions batchExecutions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ListAppender<ILoggingEvent> jobLogs;
    private ListAppender<ILoggingEvent> contextLogs;

    @BeforeEach
    void seed() {
        cleanUp();
        for (String ticker : TICKERS) {
            long id = jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?)
                    RETURNING id
                    """, Long.class, ticker, "종목" + ticker);
            jdbcTemplate.update("""
                    INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                    VALUES (?, ?, ?)
                    """, UNIVERSE, id, AS_OF.minusYears(1));
            BigDecimal close = CLOSES.get(ticker);
            jdbcTemplate.update("""
                    INSERT INTO price_daily
                        (stock_id, trade_date, open, high, low, close, adj_close, adj_factor,
                         volume, trade_value, listed_shares)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 1, 100, 100000, 1000000)
                    """, id, AS_OF, close, close, close, close, close);
        }
        jobLogs = attachTo(IndicatorBackfillJob.class);
        contextLogs = attachTo(BatchExecutionContext.class);
    }

    @AfterEach
    void cleanUp() {
        if (jobLogs != null) {
            detachFrom(IndicatorBackfillJob.class, jobLogs);
            jobLogs = null;
        }
        if (contextLogs != null) {
            detachFrom(BatchExecutionContext.class, contextLogs);
            contextLogs = null;
        }
        // MUST: indicator_value를 stock보다 먼저 지운다. FK로 정리가 중단되면 무관해 보이는
        // 다른 테스트가 깨진다(`.claude/rules/test.md`).
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        for (String table : List.of("indicator_value", "price_daily", "universe_membership")) {
            jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE stock_id IN (SELECT id FROM stock WHERE ticker = ANY (?))",
                    (Object) TICKERS.toArray(String[]::new));
        }
        jdbcTemplate.update("DELETE FROM stock WHERE ticker = ANY (?)",
                (Object) TICKERS.toArray(String[]::new));
    }

    @Test
    @DisplayName("워커 둘로 흩어 돌아도 종목마다 제 값이 저장되고 집계가 계획과 맞는다")
    void fansOutWithoutMixingValuesBetweenStocks() {
        BatchStatus status = job.run(AS_OF, AS_OF,
                BatchExecutionRecorder.TriggeredBy.MANUAL, TICKERS, null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);

        // ① 이 시험이 정말 병렬 경로를 탔는가. 클램프가 1로 깎았다면 아래 단언들은
        //    직렬 경로를 다시 재는 것이고 아무것도 지키지 않는다.
        assertThat(loggedWorkerCount())
                .as("잡이 신고한 워커 수. 1이면 클램프가 깎은 것이고 이 시험은 무의미하다")
                .hasValue(2);

        // ② 값이 종목 사이에서 섞이지 않았는가. ADJ_CLOSE는 봉 하나로 값이 나오는 지표라
        //    기대값이 결정적이다 — 워커가 남의 컨텍스트를 봤다면 여기서 갈린다.
        for (String ticker : TICKERS) {
            assertThat(storedAdjClose(ticker))
                    .as("%s의 ADJ_CLOSE", ticker)
                    .isEqualByComparingTo(CLOSES.get(ticker));
        }

        // ③ 집계가 계획과 맞는가. 팬아웃이 한 종목을 흘리면 여기서 갈린다.
        Map<String, Object> progress = jdbcTemplate.queryForMap("""
                SELECT success_count, failure_count, planned_task_count
                FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, IndicatorBackfillJob.JOB_NAME);
        assertThat(progress.get("success_count")).isEqualTo(TICKERS.size());
        assertThat(progress.get("failure_count")).isEqualTo(0);
        assertThat(progress.get("planned_task_count")).isEqualTo(TICKERS.size());
    }

    @Test
    @DisplayName("종료가 시작되면 워커가 끊기고 실행이 ABORTED로 마감된다 — 신호는 감싸지 않는다")
    void stopsCooperativelyAndDoesNotWrapTheShutdownSignal() throws Exception {
        // 루프를 길게 만든다. 짧으면 종료를 걸기 전에 끝나 시험이 종료 경로를 못 태운다.
        LocalDate from = seedEarlierTradeDates(60);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                job.run(from, AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, TICKERS, null);
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "backfill-under-test");
        runner.start();
        try {
            assertThat(awaitRunning(10_000))
                    .as("잡이 도는 것을 못 잡았다 — 이 시험은 종료 경로를 태우지 못했다."
                            + " 초록으로 접지 않는다")
                    .isTrue();
            batchExecutions.closeOpenExecutions();

            runner.join(15_000);
            assertThat(runner.isAlive())
                    .as("종료 신호 뒤에도 루프가 돌고 있다 — 협조적 정지가 워커에 안 닿았다")
                    .isFalse();
        } finally {
            runner.interrupt();
        }

        // MUST: 감싸지 않고 올라와야 한다. 감싸면 failUnexpectedly의 instanceof 조기 반환이
        // 안 걸려 **종료마다** "이미 마감된 …" 경고가 나고, 그 경고가 진짜 마감 경합을
        // 가리키기를 그만둔다(ADR-0078).
        assertThat(thrown.get()).isInstanceOf(BatchShutdownException.class);
        assertThat(contextLogs.list)
                .as("종료 신호를 실패로 세면 여기 가짜 경합 경고가 남는다")
                .noneMatch(event -> event.getFormattedMessage().contains("이미 마감된"));

        assertThat(lastStatus()).isEqualTo(BatchStatus.ABORTED.name());
    }

    /** {@code AS_OF} 앞으로 거래일을 더 심고 그 시작일을 돌려준다. */
    private LocalDate seedEarlierTradeDates(int count) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM stock WHERE ticker = ANY (?) ORDER BY id",
                Long.class, (Object) TICKERS.toArray(String[]::new));
        LocalDate from = AS_OF.minusDays(count);
        for (long id : ids) {
            for (int i = 1; i <= count; i++) {
                jdbcTemplate.update("""
                        INSERT INTO price_daily
                            (stock_id, trade_date, open, high, low, close, adj_close, adj_factor,
                             volume, trade_value, listed_shares)
                        VALUES (?, ?, 1000, 1000, 1000, 1000, 1000, 1, 100, 100000, 1000000)
                        """, id, AS_OF.minusDays(i));
            }
        }
        return from;
    }

    private boolean awaitRunning(long budgetMillis) throws InterruptedException {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Integer running = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM batch_execution
                    WHERE job_name = ? AND status = 'RUNNING'
                    """, Integer.class, IndicatorBackfillJob.JOB_NAME);
            if (running != null && running > 0) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    private String lastStatus() {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM batch_execution WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, String.class, IndicatorBackfillJob.JOB_NAME);
    }

    /** 잡이 스스로 찍은 워커 수. 문자열이 아니라 로그 <b>인자</b>에서 꺼낸다. */
    private Optional<Integer> loggedWorkerCount() {
        return jobLogs.list.stream()
                .filter(event -> event.getMessage().startsWith("종목 루프를 {}워커로"))
                .map(event -> (Integer) event.getArgumentArray()[0])
                .findFirst();
    }

    private BigDecimal storedAdjClose(String ticker) {
        return jdbcTemplate.queryForObject("""
                SELECT iv.value FROM indicator_value iv
                JOIN stock s ON s.id = iv.stock_id
                WHERE s.ticker = ? AND iv.indicator_code = ? AND iv.trade_date = ?
                """, BigDecimal.class, ticker, AdjCloseCalculator.CODE, AS_OF);
    }

    private static ListAppender<ILoggingEvent> attachTo(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        return appender;
    }

    private static void detachFrom(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }
}
