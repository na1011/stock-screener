package com.stockscreener.pipeline.lock;

import com.stockscreener.screening.preset.PresetSynchronizer;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시드된 DB에서 프리셋 시더가 <b>문장을 하나도 안 낸다</b>(ADR-0175 · 잠금 v5 4').
 *
 * <p>프리셋 표({@code screen} · {@code condition_group} · {@code screen_condition} · {@code screen_condition_threshold})는 쓰기
 * 가드의 보호 제외 표라 가드로는 문장을 못 센다 — 그래서 이 시험이 그 넷에 <b>문장 단위 계수 트리거</b>를 잠시 달고 시퀀스로
 * 센다(0행 UPDATE도 한 번으로 센다). 차이가 있으면 가드가 켜진 DB에서 배포된 jar가 아닌 JVM(이 시험 JVM)은 쓰지 않고 거절한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class PresetSeederStatementTest {

    private static final List<String> PRESET_TABLES =
            List.of("screen", "condition_group", "screen_condition", "screen_condition_threshold");

    @Autowired
    private PresetSynchronizer synchronizer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String db;

    @BeforeEach
    void setUp() {
        db = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        jdbcTemplate.update("DELETE FROM guard_setting");
        synchronizer.synchronize(); // 다른 시험이 프리셋을 흔들었을 수 있다 — 가드 끈 채로 지금 코드에 맞춘다.
        jdbcTemplate.execute("CREATE SEQUENCE IF NOT EXISTS zz_preset_stmt");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION zz_preset_stmt_count() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM nextval('zz_preset_stmt');
                    RETURN NULL;
                END $$""");
        for (String table : PRESET_TABLES) {
            jdbcTemplate.execute("CREATE OR REPLACE TRIGGER zz_preset_stmt AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON "
                    + table + " FOR EACH STATEMENT EXECUTE FUNCTION zz_preset_stmt_count()");
        }
    }

    @AfterEach
    void tearDown() {
        for (String table : PRESET_TABLES) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS zz_preset_stmt ON " + table);
        }
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS zz_preset_stmt_count()");
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS zz_preset_stmt");
        jdbcTemplate.update("DELETE FROM guard_setting");
        jdbcTemplate.update("DELETE FROM screen WHERE code = 'ZZ_LOCK_RETIRED'");
        synchronizer.synchronize();
    }

    @Test
    @DisplayName("같으면 문장 0 — 둘째 기동의 프리셋 시더는 조건을 지우고 다시 넣지 않는다")
    void secondSyncEmitsNoStatement() {
        long before = statements();

        synchronizer.synchronize();

        assertThat(statements() - before).as("둘째 동기화가 프리셋 표에 낸 문장 수").isZero();
    }

    @Test
    @DisplayName("다르면 쓰지 않고 거절 — 바뀐 조건 · 사라진 프리셋의 비활성화 둘 다 차이로 센다")
    void driftIsRefusedWithoutWriting() {
        String code = jdbcTemplate.queryForObject(
                "SELECT code FROM screen WHERE screen_type = 'PRESET' AND is_active ORDER BY code LIMIT 1", String.class);
        jdbcTemplate.update("""
                UPDATE screen_condition SET is_required = NOT is_required, weight = CASE WHEN is_required THEN 1 END
                 WHERE id = (SELECT min(sc.id) FROM screen_condition sc JOIN condition_group g ON g.id = sc.group_id
                              JOIN screen s ON s.id = g.screen_id WHERE s.code = ?)
                """, code);
        jdbcTemplate.update("""
                INSERT INTO screen (code, name, screen_type, evaluation_mode, is_active, created_at, updated_at)
                VALUES ('ZZ_LOCK_RETIRED', '사라진 프리셋', 'PRESET', 'FILTER', true, now(), now())
                """);
        guardOnForThisJvm();
        long before = statements();

        assertThatThrownBy(() -> synchronizer.synchronize())
                .isInstanceOf(CatalogWriteGate.CatalogDriftException.class)
                .hasMessageContaining(code + " 바뀜")
                .hasMessageContaining("ZZ_LOCK_RETIRED 비활성");
        assertThat(statements() - before).as("거절된 동기화가 낸 문장 수").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM screen WHERE code = 'ZZ_LOCK_RETIRED'", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("가드가 꺼진 DB(사본·시험)에서는 차이만 쓴다 — 바뀐 프리셋 하나와 비활성화")
    void driftIsWrittenWhereTheGuardIsOff() {
        jdbcTemplate.update("""
                INSERT INTO screen (code, name, screen_type, evaluation_mode, is_active, created_at, updated_at)
                VALUES ('ZZ_LOCK_RETIRED', '사라진 프리셋', 'PRESET', 'FILTER', true, now(), now())
                """);
        long before = statements();

        synchronizer.synchronize();

        assertThat(statements() - before).as("비활성화 UPDATE 하나").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM screen WHERE code = 'ZZ_LOCK_RETIRED'", Boolean.class))
                .isFalse();
    }

    private long statements() {
        // last_value는 nextval 전이면 시작값(1)이고 is_called가 거짓이다 — 둘을 함께 읽어 「낸 문장 수」로 바꾼다.
        return jdbcTemplate.queryForObject(
                "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM zz_preset_stmt", Long.class);
    }

    private void guardOnForThisJvm() {
        String me = jdbcTemplate.queryForObject("SELECT current_setting('application_name')", String.class);
        jdbcTemplate.update("INSERT INTO guard_setting (db_name, identity_prefix, note) VALUES (?, ?, 'PresetSeederStatementTest')",
                db, me); // 신원 전체 — pid 재사용에 안 걸린다
    }
}
