package com.stockscreener.pipeline.lock;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ops/write-session.sh}를 실제로 돌려 잰다(ADR-0175 · 잠금 v5 9' · 2'').
 *
 * <p>가드는 <b>신원 범위 행</b>({@code ss:s:wst-} 접두)으로만 켠다 — 이 시험이 만든 세션 이름에만 걸리고, 죽어 남아도 다른 쓰기를
 * 안 막는다. 끝에 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class WriteSessionScriptTest {

    private static final Path SCRIPT = Path.of("ops/write-session.sh");
    private static final String PREFIX = "ss:s:wst-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    private String db;
    private final List<Process> background = new ArrayList<>();
    private final List<WriteLock> locks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        db = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        jdbcTemplate.update("DELETE FROM write_lock");
        jdbcTemplate.update("DELETE FROM guard_setting");
    }

    @AfterEach
    void tearDown() {
        background.forEach(Process::destroyForcibly);
        locks.forEach(WriteLock::destroy);
        jdbcTemplate.update("DELETE FROM guard_setting");
        jdbcTemplate.update("DELETE FROM write_lock");
    }

    @Test
    @DisplayName("세션 TTL이 Java와 셸에서 같다 — 갈리면 셸이 산 세션을 만료로 보거나 그 반대다")
    void ttlMatchesJava() throws IOException {
        Matcher matcher = Pattern.compile("(?m)^WRITE_SESSION_TTL_MINUTES=(\\d+)$")
                .matcher(Files.readString(Path.of("ops/lib.sh"), StandardCharsets.UTF_8));
        assertThat(matcher.find()).isTrue();
        assertThat(Long.parseLong(matcher.group(1))).isEqualTo(WriteLock.SESSION_TTL.toMinutes());
    }

    @Test
    @DisplayName("begin → exec의 psql은 세션 신원으로 보호 표에 쓴다 · 세션 밖 신원은 가드가 거절한다 · end 뒤 exec는 거절")
    void execWritesUnderTheSessionIdentity() throws Exception {
        jdbcTemplate.update("INSERT INTO guard_setting (db_name, identity_prefix) VALUES (?, ?)", db, PREFIX);

        assertThat(run("begin", "wst-a").exit).isZero();
        Result write = run("exec", "wst-a", "--", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", db, "-Atc",
                "SELECT current_setting('application_name'); DELETE FROM market_calendar WHERE false");
        assertThat(write.exit).as(write.output).isZero();
        assertThat(write.output).contains("ss:s:wst-a");

        Result stranger = psqlAs("ss:s:wst-b", "DELETE FROM market_calendar WHERE false");
        assertThat(stranger.exit).isNotZero();
        assertThat(stranger.output).contains("쓰기 가드");

        Result secondBegin = run("begin", "wst-b");
        assertThat(secondBegin.exit).isEqualTo(1);
        assertThat(secondBegin.output).contains("wst-a");

        assertThat(run("end", "wst-a").exit).isZero();
        Result late = run("exec", "wst-a", "--", "true");
        assertThat(late.exit).isEqualTo(1);
        assertThat(late.output).contains("begin부터");
    }

    @Test
    @DisplayName("exec 도중에는 TTL이 지나도 세션이 안 만료된다 — JVM 획득도 거절 · end도 거절(L1-2) · 끝나면 만료된다")
    void runningExecKeepsTheSessionAlive() throws Exception {
        assertThat(run("begin", "wst-long").exit).isZero();
        Process sleeper = new ProcessBuilder("bash", SCRIPT.toString(), "exec", "wst-long", "--db", db, "--", "sleep", "30")
                .redirectErrorStream(true).start();
        background.add(sleeper);
        waitUntil(() -> jdbcTemplate.queryForObject(
                "SELECT cardinality(members) FROM write_lock", Integer.class) == 1, Duration.ofSeconds(10));
        // 마지막 활동을 TTL 넘게 뒤로 민다 — 산 exec 구성원이 있으므로 그래도 산 세션이다.
        jdbcTemplate.update("UPDATE write_lock SET last_activity_at = now() - interval '3 hours'");

        assertThatThrownBy(() -> jvmLock().acquire("t:during-exec")).isInstanceOf(WriteLockHeldException.class);
        Result end = run("end", "wst-long");
        assertThat(end.exit).isEqualTo(1);
        assertThat(end.output).contains("산 구성원");

        sleeper.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        // 래퍼가 kill -9로 죽으면 구성원이 남지만 죽은 구성원이다 — 마지막 활동이 TTL 밖이면 만료다.
        jdbcTemplate.update("UPDATE write_lock SET last_activity_at = now() - interval '3 hours'");
        Result expiredExec = run("exec", "wst-long", "--", "true");
        assertThat(expiredExec.exit).isEqualTo(1);
        assertThat(expiredExec.output).contains("만료");
        jvmLock().acquire("t:after-expiry");
    }

    @Test
    @DisplayName("산 대기 크론 표지가 있으면 begin을 거절한다 — 크론이 먼저 돈다")
    void beginYieldsToAWaitingCron() throws Exception {
        Process cron = new ProcessBuilder("sleep", "60").start();
        background.add(cron);
        ProcessIdentity waiter = ProcessIdentity.of(cron.toHandle()).orElseThrow();
        jdbcTemplate.update("INSERT INTO write_lock (db_name, version, waiter_pid, waiter_start, waiter_purpose, "
                + "waiter_since) VALUES (current_database(), 1, ?, ?, 'cron:runDailyUpdate', now())",
                waiter.pid(), waiter.startEpochSecond());

        Result begin = run("begin", "wst-c");
        assertThat(begin.exit).isEqualTo(1);
        assertThat(begin.output).contains("cron:runDailyUpdate");
    }

    @Test
    @DisplayName("세션 만료 경계가 셸과 Java에서 같다 — 59분 40초는 둘 다 산 세션 · 60분 5초는 둘 다 만료(분 반올림이면 갈린다)")
    void sessionExpiryBoundaryMatchesJava() throws Exception {
        assertThat(run("begin", "wst-edge").exit).isZero();
        jdbcTemplate.update("UPDATE write_lock SET members = '{}', last_activity_at = now() - interval '59 minutes 40 seconds'");

        assertThatThrownBy(() -> jvmLock().acquire("t:edge")).as("Java: 산 세션").isInstanceOf(WriteLockHeldException.class);
        assertThat(heldByOther().exit).as("lib.sh: 산 세션").isZero();
        Result begin = run("begin", "wst-other");
        assertThat(begin.exit).as("write-session.sh: 산 세션이라 넘겨받지 않는다 — " + begin.output).isEqualTo(1);

        jdbcTemplate.update("UPDATE write_lock SET last_activity_at = now() - interval '60 minutes 5 seconds'");
        assertThat(heldByOther().exit).as("lib.sh: 만료").isEqualTo(1);
        jvmLock().acquire("t:edge-expired");
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────

    private Result heldByOther() throws Exception {
        return exec(new ProcessBuilder("bash", "-c", ". ops/lib.sh; write_lock_held_by_other " + db));
    }

    private WriteLock jvmLock() {
        ProcessIdentity me = ProcessIdentity.current();
        WriteIdentity identity = new WriteIdentity(new ProcessIdentity(me.pid(), me.startEpochSecond()), null);
        WriteLock lock = new WriteLock(identity, new WriteLockStore(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"), identity.applicationName()),
                event -> { }, java.time.Clock.systemUTC(), duration -> { }, null);
        lock.enable(LockRow.HolderKind.BOOTRUN);
        locks.add(lock);
        return lock;
    }

    private Result run(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        // --db는 id 바로 뒤다(스크립트의 인자 순서).
        command.add(args[0]);
        command.add(args[1]);
        command.add("--db");
        command.add(db);
        for (int i = 2; i < args.length; i++) {
            command.add(args[i]);
        }
        return exec(new ProcessBuilder(command));
    }

    private Result psqlAs(String applicationName, String sql) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", db, "-c", sql);
        builder.environment().put("PGAPPNAME", applicationName);
        return exec(builder);
    }

    private static Result exec(ProcessBuilder builder) throws Exception {
        builder.redirectErrorStream(true);
        builder.environment().remove(WriteIdentity.SESSION_ENV);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("60초 안에 안 끝났다: " + builder.command());
        }
        return new Result(process.exitValue(), output);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(limit + " 안에 조건이 안 섰다");
            }
            Thread.sleep(100);
        }
    }

    private record Result(int exit, String output) {
    }
}
