package com.stockscreener.pipeline.lock;

import com.stockscreener.indicator.IndicatorCatalogSynchronizer;
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

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시드된 DB에서 지표 시더가 <b>문장을 하나도 안 낸다</b>(ADR-0175 · 잠금 v4 결정 4 · v5 4').
 *
 * <p>행 수가 아니라 문장 수로 잰다 — 가드를 이 JVM 신원 범위로 켜고 잠금은 안 쥔다. 가드는 문장 단위라 0행 UPDATE도 거절하므로,
 * 시더가 문장을 하나라도 내면 {@code SSWG1}로 실패한다. 차이가 있으면 쓰지 않고 {@link CatalogWriteGate.CatalogDriftException} —
 * 가드가 켜진 DB에서 배포된 jar가 아닌 JVM(이 시험 JVM)이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class CatalogSeederStatementTest {

    @Autowired
    private IndicatorCatalogSynchronizer synchronizer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String db;
    private String myPrefix;

    @BeforeEach
    void setUp() {
        db = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        String me = jdbcTemplate.queryForObject("SELECT current_setting('application_name')", String.class);
        myPrefix = me.substring(0, me.lastIndexOf(':') + 1);
        jdbcTemplate.update("DELETE FROM guard_setting");
        jdbcTemplate.update("DELETE FROM write_lock");
        synchronizer.synchronize(); // 다른 시험이 카탈로그를 흔들었을 수 있다 — 가드 끈 채로 지금 코드에 맞춘다.
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM guard_setting");
        jdbcTemplate.update("DELETE FROM indicator WHERE code = 'ZZ_LOCK_GONE'");
        synchronizer.synchronize();
    }

    @Test
    @DisplayName("같으면 문장 0 — 잠금 없이 가드가 켜져도 기동 시더가 통과한다(읽기 전용 bootRun)")
    void secondSyncEmitsNoStatement() {
        Timestamp before = jdbcTemplate.queryForObject("SELECT max(updated_at) FROM indicator", Timestamp.class);
        guardOnForThisJvm();

        synchronizer.synchronize();

        assertThat(jdbcTemplate.queryForObject("SELECT max(updated_at) FROM indicator", Timestamp.class))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("다르면 쓰지 않고 거절 — 바뀐 칸 · 사라진 지표의 비활성화 둘 다 차이로 센다")
    void driftIsRefusedWithoutWriting() {
        jdbcTemplate.update("UPDATE indicator SET name = name || ' (옛 이름)' WHERE code = 'PER'");
        jdbcTemplate.update("""
                INSERT INTO indicator (code, name, category, value_type, decimal_scale, is_active, sort_order,
                                       default_column, detail_default, created_at, updated_at)
                SELECT 'ZZ_LOCK_GONE', 'gone', category, value_type, 0, true, 999999, false, false, now(), now()
                  FROM indicator WHERE code = 'PER'
                """);
        guardOnForThisJvm();

        assertThatThrownBy(() -> synchronizer.synchronize())
                .isInstanceOf(CatalogWriteGate.CatalogDriftException.class)
                .hasMessageContaining("PER.name")
                .hasMessageContaining("ZZ_LOCK_GONE 비활성");
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM indicator WHERE code = 'PER'", String.class))
                .endsWith("(옛 이름)");
    }

    @Test
    @DisplayName("가드가 꺼진 DB(사본·시험)에서는 차이를 쓴다 — 예전 동작 그대로")
    void driftIsWrittenWhereTheGuardIsOff() {
        jdbcTemplate.update("UPDATE indicator SET name = name || ' (옛 이름)' WHERE code = 'PER'");

        synchronizer.synchronize();

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM indicator WHERE code = 'PER'", String.class))
                .doesNotEndWith("(옛 이름)");
    }

    @Test
    @DisplayName("남의 신원 범위 행만 있으면 이 JVM에는 가드가 안 걸린다 — 시더는 차이를 쓴다(트리거와 같은 술어)")
    void anotherJvmsScopedGuardDoesNotRefuseThisJvm() {
        jdbcTemplate.update("UPDATE indicator SET name = name || ' (옛 이름)' WHERE code = 'PER'");
        jdbcTemplate.update("INSERT INTO guard_setting (db_name, identity_prefix, note) VALUES (?, 'ss:j:1:', 'CatalogSeederStatementTest')",
                db);

        synchronizer.synchronize();

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM indicator WHERE code = 'PER'", String.class))
                .doesNotEndWith("(옛 이름)");
    }

    private void guardOnForThisJvm() {
        jdbcTemplate.update("INSERT INTO guard_setting (db_name, identity_prefix, note) VALUES (?, ?, 'CatalogSeederStatementTest')",
                db, myPrefix);
    }
}
