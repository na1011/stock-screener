package com.stockscreener.pipeline.lock;

import com.stockscreener.StockScreenerApplication;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹 기동의 스위퍼가 기동 잠금 토큰 반납보다 <b>먼저</b> 돈다(ADR-0175 · 잠금 결정 6) — 실제 컨텍스트에서 잰다.
 *
 * <p>가드를 이 JVM 신원 전체로 켜고 주인이 죽은 RUNNING 행을 하나 심은 뒤 컨텍스트를 띄운다. 스위퍼가 그 행을 닫는 쓰기는 토큰이
 * 있어야 통과한다 — 반납이 먼저면 {@code SSWG1}로 거절돼 기동이 실패하거나 행이 RUNNING으로 남는다. 두 리스너는 같은
 * {@code ApplicationReadyEvent}를 받으므로 순서는 {@code @Order}만이 정한다.
 */
class WriteLockStartupOrderTest {

    private static final String JOB = "zz-startup-order";

    @Test
    @DisplayName("가드 켬: 기동 스위퍼가 주인이 죽은 행을 닫은 뒤에 기동 토큰이 반납된다")
    void sweeperRunsBeforeTheBootTokenIsReleased() throws Exception {
        String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/stock_screener_test");
        String user = System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name"));
        String password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
        ProcessIdentity me = ProcessIdentity.current();
        long executionId;
        try (Connection admin = DriverManager.getConnection(url, user, password)) {
            update(admin, "DELETE FROM guard_setting");
            update(admin, "DELETE FROM write_lock");
            executionId = Long.parseLong(text(admin, """
                    INSERT INTO batch_execution (job_name, target_date, status, triggered_by, scope, started_at,
                                                 owner_pid, owner_start, owner_db)
                    VALUES ('%s', current_date, 'RUNNING', 'SCHEDULER', 'FULL', now() - interval '1 hour', %d, %d,
                            current_database())
                    RETURNING id
                    """.formatted(JOB, me.pid(), me.startEpochSecond() - 1)));
            update(admin, "INSERT INTO guard_setting (db_name, identity_prefix, note) VALUES (current_database(), '"
                    + new WriteIdentity(me, null).applicationName() + "', 'WriteLockStartupOrderTest')");
            try {
                ConfigurableApplicationContext context = new SpringApplicationBuilder(StockScreenerApplication.class)
                        .sources(TestDatabaseGuard.class)
                        .profiles("test")
                        .web(WebApplicationType.NONE)
                        .run();
                context.close();

                assertThat(text(admin, "SELECT status FROM batch_execution WHERE id = " + executionId))
                        .as("스위퍼의 쓰기가 토큰 안에서 돌았다 — 반납이 먼저였으면 거절돼 RUNNING이다")
                        .isEqualTo("ABORTED");
            } finally {
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
