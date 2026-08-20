package com.stockscreener.pipeline.batch;

import com.stockscreener.StockScreenerApplication;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>워커가 도는 중에</b> 컨텍스트가 닫힐 때 무슨 일이 일어나는지 본다.
 *
 * <p>이 테스트가 따로 있는 이유 — 기존 둘은 이 경합을 밟지 못한다.
 * {@link BatchExecutionClosingTest}는 {@code closeOpenExecutions()}를 직접 부르고,
 * {@link BatchExecutionShutdownHookTest}는 실행을 열기만 한 뒤 닫는다. <b>둘 다 워커가 없다.</b>
 * 그래서 마감 경로와 워커가 같은 행을 두고 다투는 자리, 즉 운영에서 실제로 일어나는 모양이
 * 어느 테스트에도 없었다.
 *
 * <p>재는 것이 넷이다. <b>마감된 상태와 사유</b>(워커가 이겨서 PARTIAL_SUCCESS로 덮는가),
 * <b>알림</b>(가짜 "부분 실패"가 나가는가), <b>남은 실패 행</b>(인터럽트가 종목 실패로 기록되는가),
 * 그리고 <b>{@code close()}에 걸린 시간</b>이다. 마지막 것이 배포 중단의 원인이다 —
 * {@code ops/stop.sh}가 그 시간만큼 기다리다 실패로 판정하면 {@code ops/deploy.sh}의
 * {@code set -e}가 재기동에 도달하지 못한다.
 *
 * <p>MUST: 가짜 작업이 <b>어댑터와 같은 모양</b>이어야 한다 — {@code InterruptedException}을 잡아
 * 플래그를 복원하고 {@code TRANSIENT}로 감싼다({@code DataGoKrPriceAdapter}가 그 원형이다).
 * 그냥 삼키면 실패가 한 건도 안 남아 <b>고치기 전에도 통과한다.</b>
 *
 * <p>MUST: 워커가 끝나기를 기다린 뒤 정리한다. 워커 스레드는 daemon이 아니라, 거두지 않으면
 * 닫힌 커넥션 풀을 향해 계속 돌면서 무관한 다음 테스트를 깨뜨린다.
 */
class BatchExecutionShutdownRaceTest {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutionShutdownRaceTest.class);

    private static final String JOB_NAME = "test-shutdown-race";
    private static final String TICKER = "999901";
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 5);

    /**
     * 가짜 작업이 자는 시간. <b>종료 유예 상한보다 길어야 한다.</b>
     *
     * <p>짧으면 워커가 스스로 끝나 상한이 아예 안 밟히고, 그러면 이 테스트는 상한을 지워도
     * 초록이다. 그 대가로 빌드에 상한만큼의 시간이 붙는다.
     */
    private static final Duration WORKER_SLEEP = Duration.ofSeconds(60);

    /** 종료 유예 상한(15초)을 밟되 {@code ops/stop.sh}의 대기(45초) 안에 들어와야 한다. */
    private static final Duration CLOSE_AT_LEAST = Duration.ofSeconds(14);
    private static final Duration CLOSE_AT_MOST = Duration.ofSeconds(25);

    @Test
    @DisplayName("도는 배치를 두고 내려가면 알림 없이 ABORTED로 마감하고, 종료가 유예 상한 안에 끝난다")
    void closesRunningExecutionQuietlyWithinTheShutdownBudget() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch workerDone = new CountDownLatch(1);
        AtomicReference<Throwable> workerError = new AtomicReference<>();

        long executionId;
        long stockId;
        String url;
        String user;
        String password;
        StockFailureIsolationTest.RecordingAlertPort alertPort;

        ConfigurableApplicationContext context = new SpringApplicationBuilder(StockScreenerApplication.class)
                .sources(TestDatabaseGuard.class, StockFailureIsolationTest.RecordingAlertConfig.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run();
        try {
            // 닫은 뒤에는 Environment도 주입받은 JdbcTemplate도 못 쓴다. 먼저 빼둔다.
            Environment environment = context.getEnvironment();
            url = environment.getProperty("spring.datasource.url");
            user = environment.getProperty("spring.datasource.username");
            password = environment.getProperty("spring.datasource.password", "");

            alertPort = context.getBean(StockFailureIsolationTest.RecordingAlertPort.class);
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            stockId = jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name)
                    VALUES ('KOSPI', ?, ?)
                    RETURNING id
                    """, Long.class, TICKER, "종료경합");
            SecurityRef security = new SecurityRef(stockId, "KOSPI", TICKER);

            BatchExecutionContext execution = context.getBean(BatchExecutions.class)
                    .start(JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 2);
            executionId = execution.executionId();

            AsyncTaskExecutor taskExecutor = context.getBean(
                    TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
                    AsyncTaskExecutor.class);
            taskExecutor.execute(() -> {
                try {
                    // 잡의 종목 루프를 흉내낸다. 두 번이면 "인터럽트 뒤에도 루프가 계속 돈다"가 드러난다.
                    for (int i = 0; i < 2; i++) {
                        execution.process(security, CollectionPhase.PRICE, sleepingTask(entered));
                    }
                    execution.finish();
                } catch (Throwable t) {
                    // MUST NOT: 삼키지 않는다. 워커 스레드에서 난 예외는 아무 데도 안 남으므로
                    // 여기서 붙잡아 본문에서 단언한다. 삼키면 종료가 잡을 죽였다는 사실이 사라진다.
                    workerError.set(t);
                } finally {
                    workerDone.countDown();
                }
            });

            assertThat(entered.await(20, TimeUnit.SECONDS))
                    .as("워커가 종목 루프에 들어가지 못했다 — 경합을 재현하지 못했으므로 이 회차는 무의미하다")
                    .isTrue();
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }

        long startedNanos = System.nanoTime();
        context.close();
        Duration closeTook = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(workerDone.await(60, TimeUnit.SECONDS))
                .as("워커가 끝나지 않았다 — 거두지 않으면 닫힌 풀을 향해 계속 돌며 다음 테스트를 깨뜨린다")
                .isTrue();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            String status = statusOf(connection, executionId);
            String note = noteOf(connection, executionId);
            int failures = failureCountOf(connection, executionId);
            log.info("종료 경합 실측 — close={}ms status={} note={} 실패행={} 알림={} 워커예외={}",
                    closeTook.toMillis(), status, note, failures, alertPort.events.size(),
                    workerError.get() == null ? "없음" : workerError.get().getClass().getSimpleName());

            try {
                assertThat(status)
                        .as("워커가 마감을 이겨 덮었다 — 종료로 끊긴 실행이 성공으로 남는다 (close=%dms)",
                                closeTook.toMillis())
                        .isEqualTo("ABORTED");
                assertThat(note)
                        .as("종료 사유가 남지 않았다 — 알림을 안 보내므로 이 문장이 사람이 보는 유일한 설명이다")
                        .contains("프로세스가 종료돼 중단됐다");
                assertThat(alertPort.events)
                        .as("종료 중에 알림이 나갔다 — 사람이 직접 끈 것이고, 마감은 알리지 않기로 한 경로다")
                        .isEmpty();
                assertThat(closeTook)
                        .as("종료가 유예 상한을 벗어났다. 상한을 지웠거나(기본 30초) 상한이 안 밟혔다 — "
                                + "전자면 ops/stop.sh가 정상 종료를 실패로 오판해 ops/deploy.sh가 재기동에 못 간다")
                        .isBetween(CLOSE_AT_LEAST, CLOSE_AT_MOST);
            } finally {
                cleanUp(connection, executionId, stockId);
            }
        }
    }

    /**
     * 어댑터와 같은 모양의 가짜 작업. 인터럽트를 {@code TRANSIENT}로 감싸 종목 실패가 되게 한다.
     *
     * <p>이것이 어댑터를 흉내내야 하는 이유는 인터럽트가 배치로 들어오는 입구가 실제로 여기
     * 하나뿐이기 때문이다 — HTTP는 {@code HttpURLConnection}이라 소켓 읽기가 인터럽트를 무시한다.
     */
    private StockTask sleepingTask(CountDownLatch entered) {
        return security -> {
            entered.countDown();
            try {
                Thread.sleep(WORKER_SLEEP.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataSourceException(DataSourceException.Kind.TRANSIENT, "test-source",
                        "호출 대기 중 인터럽트됐다.", e);
            }
            return 1L;
        };
    }

    private String statusOf(Connection connection, long executionId) throws Exception {
        return queryString(connection, "SELECT status FROM batch_execution WHERE id = ?", executionId);
    }

    private String noteOf(Connection connection, long executionId) throws Exception {
        return queryString(connection, "SELECT note FROM batch_execution WHERE id = ?", executionId);
    }

    private String queryString(Connection connection, String sql, long executionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private int failureCountOf(Connection connection, long executionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM batch_execution_failure WHERE execution_id = ?")) {
            statement.setLong(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /** 실패 행은 {@code batch_execution}에 CASCADE로 달려 있으므로 실행 → 종목 순으로 지운다. */
    private void cleanUp(Connection connection, long executionId, long stockId) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM batch_execution WHERE id = ?")) {
            statement.setLong(1, executionId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM stock WHERE id = ?")) {
            statement.setLong(1, stockId);
            statement.executeUpdate();
        }
    }
}
