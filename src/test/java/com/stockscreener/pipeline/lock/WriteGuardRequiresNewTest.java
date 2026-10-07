package com.stockscreener.pipeline.lock;

import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code REQUIRES_NEW} 경로(실행 기록 · 종목 작업)는 호출부와 다른 풀 연결로 새 트랜잭션을 연다 — 그 연결도 이 JVM의 쓰기 신원을
 * 싣고 있어 <b>토큰 없이는 거절되고 토큰을 쥐면 통과한다</b>(ADR-0175 · 잠금 결정 14의 대조 시험). 신원이 연결마다 실리지 않으면
 * (트랜잭션 안 {@code SET}·한 연결에만) 이 경로의 쓰기는 가드를 비켜 가거나, 쥔 JVM의 쓰기가 거절된다.
 *
 * <p>가드는 이 JVM 신원 범위({@code ss:j:<pid>:})로만 켠다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class WriteGuardRequiresNewTest {

    private static final String JOB = "zz-requires-new";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BatchExecutionRecorder recorder;

    @Autowired
    private WriteLock writeLock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM guard_setting");
        jdbcTemplate.update("DELETE FROM write_lock");
        // 범위는 연결이 아니라 프로세스에서 짓는다 — 연결에 신원이 안 실렸으면 그 쓰기는 이 범위 밖이라 가드를 비켜 간다(대조군).
        jdbcTemplate.update("INSERT INTO guard_setting (db_name, identity_prefix, note) VALUES (current_database(), ?, ?)",
                "ss:j:" + ProcessIdentity.current().pid() + ":", "WriteGuardRequiresNewTest");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM guard_setting");
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ?", JOB);
        jdbcTemplate.update("DELETE FROM write_lock");
    }

    @Test
    @DisplayName("REQUIRES_NEW 실행 기록: 토큰 없으면 SSWG1로 거절 · 이 JVM이 쥐면 통과")
    void requiresNewWritesNeedTheToken() {
        assertThatThrownBy(() -> recorder.start(JOB, LocalDate.of(2026, 10, 7),
                BatchExecutionRecorder.TriggeredBy.MANUAL, 0, BatchExecutionRecorder.DeclaredScope.FULL))
                .hasMessageContaining("쓰기 가드");
        assertThat(rows()).isZero();

        try (WriteLock.Token token = writeLock.acquire("t:requires-new")) {
            recorder.start(JOB, LocalDate.of(2026, 10, 7), BatchExecutionRecorder.TriggeredBy.MANUAL, 0,
                    BatchExecutionRecorder.DeclaredScope.FULL);
        }
        assertThat(rows()).isEqualTo(1);
    }

    private int rows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM batch_execution WHERE job_name = ?", Integer.class, JOB);
    }
}
