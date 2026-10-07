package com.stockscreener.pipeline.lock;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V66의 DB 강제 — 스위치 · 신원 술어 · 보호 제외 · 이벤트 트리거 · {@code search_path=''}(ADR-0175 · 잠금 v4.1 「SQL 기제」).
 *
 * <p><b>전부 한 트랜잭션 안에서 하고 되돌린다.</b> 스위치 행({@code guard_setting})을 커밋하면 공유 시험 DB에서 이 JVM 밖의 쓰기까지
 * 막히고, 도중에 죽으면 켠 채 남는다(10-06 오염 꼴). 트리거는 같은 트랜잭션의 커밋 안 한 행을 보므로 기제는 그대로 잰다 —
 * 다른 연결이 그 행을 못 보는 것은 반대로 {@code WriteGuardAcrossConnectionsTest}가 신원 범위 행으로 잰다.
 *
 * <p>신원은 {@code SET LOCAL application_name}으로 바꾼다 — 가드는 {@code current_setting('application_name')}만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class WriteGuardTriggerTest {

    private static final String GUARD_STATE = "SSWG1";

    @Autowired
    private DataSource dataSource;

    private Connection connection;
    private String db;

    @BeforeEach
    void open() throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        db = scalar("SELECT current_database()");
        // 시작 상태를 이 트랜잭션 안에서 비운다 — 커밋하지 않으므로 다른 시험의 잠금 행은 그대로다.
        exec("DELETE FROM write_lock");
        exec("DELETE FROM guard_setting");
    }

    @AfterEach
    void close() throws SQLException {
        connection.rollback();
        connection.setAutoCommit(true);
        connection.close();
    }

    @Test
    @DisplayName("스위치: 이 DB 이름 행이 없으면 꺼짐 · 다른 DB 이름 행도 꺼짐 · 이 이름이면 켜짐 — 무신원 쓰기가 갈린다")
    void switchIsTheRowForThisDatabase() throws SQLException {
        as("psql");
        assertThat(writes("UPDATE market_calendar SET exchange_code = exchange_code WHERE false")).isTrue();

        exec("INSERT INTO guard_setting (db_name) VALUES ('stock_screener_copy_of_something')");
        assertThat(writes("UPDATE market_calendar SET exchange_code = exchange_code WHERE false")).isTrue();

        exec("INSERT INTO guard_setting (db_name) VALUES ('" + db + "')");
        assertThat(writes("UPDATE market_calendar SET exchange_code = exchange_code WHERE false"))
                .as("0행 문장도 문장 단위 트리거가 발화한다 — 시더가 같으면 문장을 안 내야 하는 이유")
                .isFalse();
        assertThat(writes("TRUNCATE market_calendar")).isFalse();
    }

    @Test
    @DisplayName("JVM 보유: 그 신원만 통과 — 같은 pid 다른 시작 초 · 다른 pid · psql · pg_restore는 거절")
    void jvmHolderPassesOthersAreRefused() throws SQLException {
        guardOn(null);
        exec("INSERT INTO write_lock (db_name, version, holder_kind, token, purpose, holder_pid, holder_start) "
                + "VALUES (current_database(), 1, 'BOOTRUN', gen_random_uuid(), 'job:t', 4242, 1791018222)");

        as("ss:j:4242:1791018222");
        assertThat(writes("DELETE FROM market_calendar WHERE false")).isTrue();
        for (String stranger : List.of("ss:j:4242:1791018223", "ss:j:4243:1791018222", "psql", "pg_restore", "",
                "ss:j:4242:17910182220", "ss:s:4242")) {
            as(stranger);
            assertThat(writes("DELETE FROM market_calendar WHERE false")).as("신원 %s", stranger).isFalse();
        }
    }

    @Test
    @DisplayName("세션 보유: ss:s:<id>와 합류 JVM ss:s:<id>:j:…만 통과 — 접두가 같은 다른 세션은 거절")
    void sessionHolderPassesItsMembersOnly() throws SQLException {
        guardOn(null);
        exec("INSERT INTO write_lock (db_name, version, holder_kind, token, purpose, session_id, last_activity_at) "
                + "VALUES (current_database(), 1, 'SESSION', gen_random_uuid(), 'session:abc', 'abc', now())");

        for (String member : List.of("ss:s:abc", "ss:s:abc:j:4242:1791018222")) {
            as(member);
            assertThat(writes("DELETE FROM market_calendar WHERE false")).as("신원 %s", member).isTrue();
        }
        for (String stranger : List.of("ss:s:abcd", "ss:s:ab", "ss:s:abcd:j:1:2", "ss:s:abc:x", "ss:j:4242:1791018222")) {
            as(stranger);
            assertThat(writes("DELETE FROM market_calendar WHERE false")).as("신원 %s", stranger).isFalse();
        }
    }

    @Test
    @DisplayName("보호 제외 표는 가드가 켜져도 누구나 쓴다 — 로그인·화면 저장·잠금 획득이 안 막힌다")
    void exemptTablesStayWritable() throws SQLException {
        guardOn(null);
        as("psql");
        for (String table : List.of("allowed_email", "app_user", "interaction_log", "screen", "screen_condition",
                "screen_condition_threshold", "condition_group", "watchlist_item", "write_lock", "guard_setting",
                "flyway_schema_history")) {
            assertThat(writes("DELETE FROM " + table + " WHERE false")).as(table).isTrue();
        }
    }

    @Test
    @DisplayName("신원 범위: 접두가 있으면 그 접두의 신원만 검사한다 — 시험 JVM이 자기만 켠다")
    void identityPrefixScopesTheGuard() throws SQLException {
        guardOn("ss:j:4242:");
        as("psql");
        assertThat(writes("DELETE FROM market_calendar WHERE false")).isTrue();
        as("ss:j:4242:1791018222");
        assertThat(writes("DELETE FROM market_calendar WHERE false")).as("범위 안 · 보유자 아님").isFalse();
        as("ss:j:4243:1");
        assertThat(writes("DELETE FROM market_calendar WHERE false")).as("범위 밖").isTrue();
    }

    @Test
    @DisplayName("search_path=''(pg_restore)에서도 가드 함수가 이름을 푼다 — 보유자는 통과, 무신원은 거절")
    void guardResolvesNamesWithEmptySearchPath() throws SQLException {
        guardOn(null);
        exec("INSERT INTO write_lock (db_name, version, holder_kind, token, purpose, session_id, last_activity_at) "
                + "VALUES (current_database(), 1, 'SESSION', gen_random_uuid(), 'session:r', 'r', now())");
        exec("SET LOCAL search_path = ''");
        as("ss:s:r");
        assertThat(writes("DELETE FROM public.market_calendar WHERE false")).isTrue();
        as("pg_restore");
        assertThat(writes("DELETE FROM public.market_calendar WHERE false")).isFalse();
    }

    @Test
    @DisplayName("새 표는 만들어지는 순간 가드가 붙는다 — 일반·CTAS·SELECT INTO·UNLOGGED·파티션 · 임시 표·다른 스키마는 안 붙는다")
    void eventTriggerAttachesTheGuardToNewTables() throws SQLException {
        exec("CREATE TABLE public.wg_plain (id int)");
        exec("CREATE TABLE public.wg_ctas AS SELECT 1 AS id");
        exec("SELECT 1 AS id INTO public.wg_select_into");
        exec("CREATE UNLOGGED TABLE public.wg_unlogged (id int)");
        exec("CREATE TABLE public.wg_parent (id int) PARTITION BY RANGE (id)");
        exec("CREATE TABLE public.wg_child PARTITION OF public.wg_parent FOR VALUES FROM (0) TO (10)");
        exec("CREATE TABLE public.wg_like (LIKE public.market_calendar)");
        exec("CREATE TEMP TABLE wg_temp (id int)");
        exec("CREATE SCHEMA wg_other");
        exec("CREATE TABLE wg_other.wg_elsewhere (id int)");
        exec("CREATE TABLE IF NOT EXISTS public.wg_plain (id int)");

        assertThat(guarded("public.wg_plain")).isTrue();
        assertThat(guarded("public.wg_ctas")).isTrue();
        assertThat(guarded("public.wg_select_into")).isTrue();
        assertThat(guarded("public.wg_unlogged")).isTrue();
        assertThat(guarded("public.wg_parent")).isTrue();
        assertThat(guarded("public.wg_child")).isTrue();
        assertThat(guarded("public.wg_like")).isTrue();
        assertThat(guarded("pg_temp.wg_temp")).isFalse();
        assertThat(guarded("wg_other.wg_elsewhere")).isFalse();

        guardOn(null);
        as("psql");
        assertThat(writes("INSERT INTO public.wg_plain VALUES (1)")).isFalse();
        assertThat(writes("INSERT INTO wg_temp VALUES (1)")).isTrue();
    }

    @Test
    @DisplayName("성질: public의 보호 제외 밖 표는 전부 가드가 있고, 제외 표는 하나도 없다")
    void everyProtectedTableCarriesTheGuard() throws SQLException {
        List<String> missing = new ArrayList<>();
        List<String> exemptButGuarded = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT c.relname, public.write_guard_exempt(c.relname) AS exempt,
                            EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = c.oid AND t.tgname = 'write_guard') AS guarded
                       FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relpersistence <> 't'
                     """)) {
            while (rs.next()) {
                boolean exempt = rs.getBoolean("exempt");
                boolean guarded = rs.getBoolean("guarded");
                if (!exempt && !guarded) {
                    missing.add(rs.getString("relname"));
                }
                if (exempt && guarded) {
                    exemptButGuarded.add(rs.getString("relname"));
                }
            }
        }
        assertThat(missing).as("가드가 없는 보호 표").isEmpty();
        assertThat(exemptButGuarded).as("가드가 붙은 제외 표").isEmpty();
        assertThat(scalar("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'public' AND c.relkind = 'r' AND public.write_guard_exempt(c.relname)"))
                .as("제외 표 열하나가 다 있다(이름이 틀리면 그 표가 보호돼 로그인이 깨진다)")
                .isEqualTo("11");
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────

    private void guardOn(String prefix) throws SQLException {
        exec("INSERT INTO guard_setting (db_name, identity_prefix) VALUES ('" + db + "', "
                + (prefix == null ? "NULL" : "'" + prefix + "'") + ")");
    }

    private void as(String applicationName) throws SQLException {
        exec("SET LOCAL application_name = '" + applicationName + "'");
    }

    /** 문장이 통과했으면 참, 가드가 거절했으면 거짓. 그 밖의 실패는 그대로 던진다. */
    private boolean writes(String sql) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.releaseSavepoint(savepoint);
            return true;
        } catch (SQLException e) {
            connection.rollback(savepoint);
            if (GUARD_STATE.equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    private boolean guarded(String relation) throws SQLException {
        return "t".equals(scalar("SELECT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = '" + relation
                + "'::regclass AND tgname = 'write_guard')"));
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
