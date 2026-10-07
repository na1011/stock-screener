package com.stockscreener.pipeline.lock;

import com.stockscreener.StockScreenerApplication;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchExecutions;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가드가 켜진 DB에서 도중 종료(SIGTERM → 컨텍스트 닫힘)가 와도 ADR-0078의 종료 마감({@code ABORTED} + note)이 적힌다 — 잠금
 * 반납이 그 마감보다 <b>뒤</b>라서다(ADR-0175 · 잠금 결정 6: 반납은 컨텍스트가 닫히고 빈이 파괴된 뒤 종료 처리기가 한다).
 *
 * <p>자식 JVM을 띄우지 않는다 — SIGTERM이 하는 일은 Spring의 종료 훅이 {@code context.close()}를 부르는 것이고, 순서의 정본은
 * 그 안(컨텍스트 닫힘 이벤트 → 빈 파괴)이다. 그래서 컨텍스트를 따로 띄워 실제로 닫는다({@code BatchExecutionShutdownHookTest}와
 * 같은 꼴). 종료 처리기({@code SpringApplication.getShutdownHandlers()})는 JVM이 끝날 때만 돌므로 여기서는 손으로 부른다.
 *
 * <p>가드는 이 JVM 신원 범위({@code ss:j:<pid>:})로만 켠다. 확인은 그 범위 밖 신원의 독립 연결로 한다.
 */
class WriteLockShutdownOrderTest {

    private static final String JOB = "zz-shutdown-order";

    @Test
    @DisplayName("가드 켬 · 쓰는 중 컨텍스트 종료: 종료 마감(ABORTED·note)이 적히고, 그 뒤에 닫힌 토큰은 반납하지 않으며 종료 처리기가 반납한다")
    void shutdownMarkingPrecedesTheRelease() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StockScreenerApplication.class)
                .sources(TestDatabaseGuard.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run();
        String url = context.getEnvironment().getProperty("spring.datasource.url");
        String user = context.getEnvironment().getProperty("spring.datasource.username");
        String password = context.getEnvironment().getProperty("spring.datasource.password", "");
        WriteLock lock = context.getBean(WriteLock.class);
        ProcessIdentity me = ProcessIdentity.current();
        long executionId;
        WriteLock.Token token;
        try (Connection admin = DriverManager.getConnection(url, user, password)) {
            update(admin, "DELETE FROM write_lock");
            update(admin, "DELETE FROM guard_setting");
            update(admin, "INSERT INTO guard_setting (db_name, identity_prefix, note) VALUES (current_database(), 'ss:j:"
                    + me.pid() + ":', 'WriteLockShutdownOrderTest')");
            try {
                token = lock.acquire("job:" + JOB);
                executionId = context.getBean(BatchExecutions.class)
                        .start(JOB, LocalDate.of(2026, 10, 7), BatchExecutionRecorder.TriggeredBy.MANUAL, 1,
                                BatchExecutionRecorder.DeclaredScope.FULL)
                        .executionId();
            } catch (RuntimeException e) {
                context.close();
                throw e;
            }

            context.close();

            assertThat(text(admin, "SELECT status FROM batch_execution WHERE id = " + executionId))
                    .as("가드가 켜진 채 종료 마감이 적혔다 — 반납이 마감보다 먼저였으면 SSWG1로 거절돼 RUNNING이다")
                    .isEqualTo("ABORTED");
            assertThat(text(admin, "SELECT note FROM batch_execution WHERE id = " + executionId))
                    .contains("프로세스가 종료돼 중단됐다");

            // 잡 스레드가 끝나며 토큰을 닫는 자리 — 종료 중이면 반납하지 않는다(늦은 쓰기가 남아 있을 수 있다).
            token.close();
            assertThat(text(admin, "SELECT holder_pid::text FROM write_lock WHERE db_name = current_database()"))
                    .as("종료 중 닫힌 토큰은 반납하지 않는다").isEqualTo(Long.toString(me.pid()));

            lock.releaseAtExit();
            assertThat(text(admin, "SELECT coalesce(holder_kind, '') FROM write_lock WHERE db_name = current_database()"))
                    .as("종료 처리기가 맨 마지막에 반납한다").isEmpty();
        } finally {
            try (Connection admin = DriverManager.getConnection(url, user, password)) {
                update(admin, "DELETE FROM guard_setting");
                update(admin, "DELETE FROM batch_execution WHERE job_name = '" + JOB + "'");
                update(admin, "DELETE FROM write_lock");
            }
        }
    }

    private static void update(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static String text(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
