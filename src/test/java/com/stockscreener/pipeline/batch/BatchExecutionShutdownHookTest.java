package com.stockscreener.pipeline.batch;

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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨텍스트가 <b>실제로 닫힐 때</b> 열려 있던 실행이 마감되는지 본다.
 *
 * <p>이 테스트가 따로 있는 이유 — {@link BatchExecutionClosingTest}는
 * {@code closeOpenExecutions()}를 직접 부르므로 <b>이벤트 배선을 전혀 검증하지 않는다.</b>
 * 애너테이션을 지워도 그쪽은 전부 초록이다. 여기서 검증하는 것은 {@code BatchExecutions}가
 * {@code ContextClosedEvent}를 실제로 받아 <b>그 시점에 아직 DB에 쓸 수 있다</b>는 것이다.
 * 배선이 끊기면 종료 훅은 아무 말 없이 안 돌고 행은 RUNNING으로 남는다.
 *
 * <p>워커가 <b>도는 중에</b> 닫히는 경합은 여기서 보지 않는다 —
 * {@link BatchExecutionShutdownRaceTest}가 답한다.
 *
 * <p>공유 {@code @SpringBootTest} 컨텍스트를 쓸 수 없다. 닫는 것이 검증 대상이라 컨텍스트를
 * 따로 띄우고, 닫은 뒤에는 주입받은 {@code JdbcTemplate}이 죽으므로 <b>독립 커넥션</b>으로
 * 확인한다.
 *
 * <p>MUST: {@code .sources(TestDatabaseGuard.class)}를 명시한다. {@code SpringApplicationBuilder}는
 * {@code @Import}를 타지 않으므로, 빠뜨리면 이 테스트가 개발 DB를 향해도 아무도 막지 않는다.
 */
class BatchExecutionShutdownHookTest {

    private static final String JOB_NAME = "test-shutdown-hook";
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 8, 5);

    @Test
    @DisplayName("컨텍스트를 닫으면 열려 있던 실행이 ABORTED로 마감된다")
    void closesOpenExecutionWhenContextShutsDown() throws Exception {
        long executionId;
        String url;
        String user;
        String password;

        ConfigurableApplicationContext context = new SpringApplicationBuilder(StockScreenerApplication.class)
                .sources(TestDatabaseGuard.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run();
        try {
            // 닫은 뒤에는 Environment도 못 읽으므로 접속 정보를 먼저 빼둔다.
            url = context.getEnvironment().getProperty("spring.datasource.url");
            user = context.getEnvironment().getProperty("spring.datasource.username");
            password = context.getEnvironment().getProperty("spring.datasource.password", "");

            executionId = context.getBean(BatchExecutions.class)
                    .start(JOB_NAME, TARGET_DATE, BatchExecutionRecorder.TriggeredBy.MANUAL, 1)
                    .executionId();
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }

        context.close();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(statusOf(connection, executionId))
                    .as("컨텍스트 종료 시 ContextClosedEvent 리스너가 돌지 않았거나 그 시점에 DB를 못 썼다")
                    .isEqualTo("ABORTED");
            delete(connection, executionId);
        }
    }

    private String statusOf(Connection connection, long executionId) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT status FROM batch_execution WHERE id = ?")) {
            statement.setLong(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private void delete(Connection connection, long executionId) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM batch_execution WHERE id = ?")) {
            statement.setLong(1, executionId);
            statement.executeUpdate();
        }
    }
}
