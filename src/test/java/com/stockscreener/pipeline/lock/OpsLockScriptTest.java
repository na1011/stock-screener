package com.stockscreener.pipeline.lock;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * {@code ops/stop.sh}·{@code ops/crons-done.sh}가 쓰기 잠금과 실행 행의 주인을 어떻게 읽는지 실제로 돌려 잰다(ADR-0175 · 잠금
 * 결정 10·11).
 *
 * <p>가짜 홈({@code HOME})에 앱 PID 파일을 두고, 명령줄에 {@code app.jar}가 보이는 가짜 앱({@code exec -a … sleep})을 그 PID로
 * 띄운다 — 스크립트는 그 홈의 것만 보고 그 PID만 죽인다. DB는 이 시험 DB({@code DB_NAME})다.
 *
 * <p>{@code stop.sh}는 잠금 판정 앞에서 {@code crons-done.sh}를 부르는데, 그 판정의 마지막 단계(다음 명목 발화까지 5분)가 벽시계에
 * 묶여 있다 — 그 창 안이면 잠금 판정에 닿지 못하므로 시험을 건너뛴다(평일 크론 시각 앞 5분).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class OpsLockScriptTest {

    private static final String JOB = "zz-ops-lock";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path home;

    private String db;
    private Process app;
    private ProcessIdentity appIdentity;
    private final List<Process> others = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        db = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        jdbcTemplate.update("DELETE FROM write_lock");
        jdbcTemplate.update("DELETE FROM guard_setting");
        Path deploy = home.resolve("Library/Application Support/stock-screener");
        Files.createDirectories(deploy);
        Files.createDirectories(home.resolve("Library/Logs/stock-screener"));
        app = new ProcessBuilder("bash", "-c", "exec -a 'java -jar " + deploy.resolve("app.jar") + "' sleep 600").start();
        appIdentity = waitForIdentity(app);
        Files.writeString(deploy.resolve("app.pid"), Long.toString(app.pid()));
    }

    @AfterEach
    void tearDown() {
        app.destroyForcibly();
        others.forEach(Process::destroyForcibly);
        jdbcTemplate.update("DELETE FROM guard_setting"); // 먼저 — 범위가 NULL인 행이 남아 있으면 아래 정리가 거절된다
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ?", JOB);
        jdbcTemplate.update("DELETE FROM write_lock");
    }

    @Test
    @DisplayName("stop.sh: 앱 JVM이 잠금을 쥐면 내리지 않는다 · 남(bootRun)이 쥔 것은 막지 않는다(결정 11)")
    void stopRefusesOnlyWhileTheAppHoldsTheLock() throws Exception {
        holdAs("WEB", appIdentity, "cron:runDailyUpdate");

        Result refused = run("ops/stop.sh", "--app");
        assumeFalse(refused.output.contains("재기동 창이 걸칠 수 있다"), "다음 명목 발화 5분 앞 — 잠금 판정에 닿지 못한다");
        assertThat(refused.exit).as(refused.output).isEqualTo(1);
        assertThat(refused.output).contains("앱 JVM이 쓰기 잠금을 쥐고 있다").contains("cron:runDailyUpdate");
        assertThat(app.isAlive()).isTrue();

        jdbcTemplate.update("DELETE FROM write_lock");
        Process bootRun = new ProcessBuilder("sleep", "600").start();
        others.add(bootRun);
        holdAs("BOOTRUN", ProcessIdentity.of(bootRun.toHandle()).orElseThrow(), "job:financial-refill");

        Result stopped = run("ops/stop.sh", "--app");
        assumeFalse(stopped.output.contains("재기동 창이 걸칠 수 있다"), "다음 명목 발화 5분 앞");
        // rc는 :8080을 이 기계의 다른 앱이 잡고 있으면 1이다 — 종료 줄과 프로세스로 잰다.
        assertThat(stopped.output).as(stopped.output).contains("[stop] 앱 종료").doesNotContain("쓰기 잠금을 쥐고 있다");
        assertThat(app.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("crons-done.sh: 주인이 적힌 RUNNING은 앱 JVM의 것만 센다 — bootRun이 연 꼬리는 앱을 막지 않는다(결정 10)")
    void cronsDoneCountsOnlyTheAppsOwnRows() throws Exception {
        Process bootRun = new ProcessBuilder("sleep", "600").start();
        others.add(bootRun);
        ProcessIdentity tail = ProcessIdentity.of(bootRun.toHandle()).orElseThrow();
        Thread.sleep(1100); // 행의 started_at이 가짜 앱 기동 뒤가 되게 — 주인 없는 옛 규칙이면 이 행을 앱 것으로 센다.
        running("GAP_FILL", tail);

        Result foreign = run("ops/crons-done.sh");
        assertThat(foreign.output).as(foreign.output).doesNotContain("RUNNING이다");

        running("SCHEDULER", appIdentity);
        Result mine = run("ops/crons-done.sh");
        assertThat(mine.exit).as(mine.output).isEqualTo(1);
        assertThat(mine.output).contains("이 앱 JVM의 실행 1개가 RUNNING이다");
    }

    @Test
    @DisplayName("write_lock_held_by_other는 셋으로 답한다 — 쥠 0 · 안 쥠 1 · 물어보지 못함 2(「안 쥠」으로 접지 않는다)")
    void heldByOtherHasThreeAnswers() throws Exception {
        assertThat(heldByOther(db).exit).as("비었음").isEqualTo(1);

        Process bootRun = new ProcessBuilder("sleep", "600").start();
        others.add(bootRun);
        holdAs("BOOTRUN", ProcessIdentity.of(bootRun.toHandle()).orElseThrow(), "job:backfill");
        Result held = heldByOther(db);
        assertThat(held.exit).as(held.output).isZero();
        assertThat(held.output).contains("BOOTRUN pid " + bootRun.pid());

        Result unknown = heldByOther(NO_SUCH_DB);
        assertThat(unknown.exit).as("접속 실패는 판정 못 함이다: " + unknown.output).isEqualTo(2);
    }

    @Test
    @DisplayName("보유자를 물어보지 못하면 start.sh·deploy.sh가 멈춘다 — 게이트·뒤 단계의 점검에 기대지 않는다")
    void startAndDeployStopWhenTheHolderCannotBeAsked() throws Exception {
        Path deploy = home.resolve("Library/Application Support/stock-screener");
        Files.delete(deploy.resolve("app.pid"));
        Files.writeString(deploy.resolve("run-app.sh"), "#!/bin/bash\nexit 0\n");

        Result start = runOn(NO_SUCH_DB, "ops/start.sh", "--app");
        assertThat(start.exit).as(start.output).isEqualTo(1);
        assertThat(start.output).contains("[start] 쓰기 잠금 보유자를 판정하지 못했다");
        assertThat(deploy.resolve("app.pid")).as("앱을 띄우지 않았다").doesNotExist();

        Result gate = runOn(NO_SUCH_DB, "-c", "set -euo pipefail; . ops/lib.sh; FORCE=''; "
                + "eval \"$(sed -n '/^lock_gate() {/,/^}/p' ops/deploy.sh)\"; lock_gate; echo 통과");
        assertThat(gate.exit).as(gate.output).isEqualTo(1);
        assertThat(gate.output).contains("[deploy] 쓰기 잠금 보유자를 판정하지 못했다").doesNotContain("통과");
    }

    @Test
    @DisplayName("deploy.sh의 가드 점검 둘이 V66 전 DB(guard_setting 없음)에서 통과한다 — 잠금 jar를 자기 deploy.sh로 처음 배포할 수 있다")
    void deployGuardsPassOnADatabaseBeforeV66() throws Exception {
        String before = db + "_v65";
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + before);
        jdbcTemplate.execute("CREATE DATABASE " + before);
        try {
            String url;
            String user;
            try (java.sql.Connection pooled = jdbcTemplate.getDataSource().getConnection()) {
                url = pooled.getMetaData().getURL();
                user = pooled.getMetaData().getUserName();
            }
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                    url.substring(0, url.lastIndexOf('/') + 1) + before, user, "")) {
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE flyway_schema_history (version varchar(50), success boolean NOT NULL)");
                    for (String version : migrationVersionsBeforeTheLock()) {
                        statement.execute("INSERT INTO flyway_schema_history VALUES ('" + version + "', true)");
                    }
                }
            }
            Result prod = deployFunction("prod_migration_guard", java.util.Map.of("DB_NAME", before));
            assertThat(prod.exit).as(prod.output).isZero();
            assertThat(prod.output).contains("통과");

            Result test = deployFunction("test_db_guard",
                    java.util.Map.of("TEST_DB_URL", "jdbc:postgresql://localhost:5432/" + before));
            assertThat(test.exit).as(test.output).isZero();
            assertThat(test.output).contains("통과");
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + before);
        }
    }

    @Test
    @DisplayName("시험 DB 스위치 잔여: 죽은 시험 JVM의 전체 신원 행은 무해(통과) · (전부)·시작 초 없는 옛 꼴은 멈춤")
    void testDatabaseGuardResidueIsJudgedByIdentity() throws Exception {
        java.util.Map<String, String> env = java.util.Map.of("TEST_DB_URL", "jdbc:postgresql://localhost:5432/" + db);
        jdbcTemplate.update("INSERT INTO guard_setting (db_name, identity_prefix) VALUES (current_database(), ?)",
                "ss:j:" + appIdentity.pid() + ":" + (appIdentity.startEpochSecond() - 1));
        Result dead = deployFunction("test_db_guard", env);
        assertThat(dead.exit).as(dead.output).isZero();
        assertThat(dead.output).contains("무해").contains("통과");

        jdbcTemplate.update("UPDATE guard_setting SET identity_prefix = 'ss:j:" + appIdentity.pid() + ":'");
        Result legacy = deployFunction("test_db_guard", env);
        assertThat(legacy.exit).as(legacy.output).isEqualTo(1);

        jdbcTemplate.update("UPDATE guard_setting SET identity_prefix = NULL");
        Result all = deployFunction("test_db_guard", env);
        assertThat(all.exit).as(all.output).isEqualTo(1);
        assertThat(all.output).contains("(전부)");
    }

    @Test
    @DisplayName("deploy.sh는 적재 JVM(bootRun)이 돌고 있으면 멈춘다 — 배포를 가로지르는 옛 jar bootRun의 주인 없는 행을 지킨다")
    void deployStopsWhileALoadJvmRuns() throws Exception {
        // 적재 JVM 판정(stray_load_pids)은 명령줄의 --job=과 실행 이름 java를 본다 — 같은 모양의 무해한 프로세스(perl의 sleep)다.
        Process load = new ProcessBuilder("bash", "-c", "exec -a java perl -e 'sleep 600' -- --job=backfill").start();
        others.add(load);
        Thread.sleep(300);

        Result blocked = deployFunction("load_gate", java.util.Map.of());
        assertThat(blocked.exit).as(blocked.output).isEqualTo(1);
        assertThat(blocked.output).contains("적재 JVM(bootRun)이 돌고 있다").contains(Long.toString(load.pid()));

        load.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        Result clear = deployFunction("load_gate", java.util.Map.of());
        assertThat(clear.output).as("이 기계에 다른 bootRun이 없을 때").contains("통과");
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────

    /** 잠금 마이그레이션(write_guard_check) 앞의 운영 마이그레이션 버전 — V66 전 DB의 이력. */
    /**
     * 잠금 마이그레이션(가드 함수를 처음 만드는 것)보다 번호가 작은 것 전부 — 「잠금 전 DB」의 이력이다. 「가드 함수를 안 담은 것」으로
     * 고르면 잠금 뒤에 생긴 마이그레이션(V67~)까지 넣어 「V66만 빠진」 있을 수 없는 이력이 된다(2026-10-13 V67이 그렇게 붉혔다).
     */
    private static List<String> migrationVersionsBeforeTheLock() throws Exception {
        java.util.Map<Integer, Path> byVersion = new java.util.TreeMap<>();
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path file : files.toList()) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^V(\\d+)__").matcher(file.getFileName().toString());
                if (m.find()) {
                    byVersion.put(Integer.parseInt(m.group(1)), file);
                }
            }
        }
        int lock = Integer.MAX_VALUE;
        for (var entry : byVersion.entrySet()) {
            if (Files.readString(entry.getValue()).contains("CREATE OR REPLACE FUNCTION public.write_guard_check()")) {
                lock = Math.min(lock, entry.getKey());
            }
        }
        assertThat(lock).as("가드 함수를 만드는 마이그레이션이 있어야 한다").isLessThan(Integer.MAX_VALUE);
        List<String> versions = new ArrayList<>();
        for (int version : byVersion.keySet()) {
            if (version < lock) {
                versions.add(String.valueOf(version));
            }
        }
        return versions;
    }

    /** {@code ops/deploy.sh}의 함수 하나만 떼어 돌린다 — 빌드·종료·스테이징은 안 탄다. */
    private Result deployFunction(String function, java.util.Map<String, String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", "set -euo pipefail; . ops/lib.sh; FORCE=''; ROOT=\"$PWD\"; "
                + "eval \"$(sed -n '/^" + function + "() {/,/^}/p' ops/deploy.sh)\"; " + function + "; echo 통과");
        builder.environment().put("HOME", home.toString());
        builder.environment().put("DB_NAME", db);
        builder.environment().putAll(env);
        builder.environment().remove(WriteIdentity.SESSION_ENV);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("90초 안에 안 끝났다: " + function);
        }
        return new Result(process.exitValue(), output);
    }

    private static final String NO_SUCH_DB = "zz_no_such_db_p3lock";

    private Result heldByOther(String database) throws Exception {
        return runOn(database, "-c", ". ops/lib.sh; write_lock_held_by_other " + database);
    }

    private void holdAs(String kind, ProcessIdentity holder, String purpose) {
        jdbcTemplate.update("""
                INSERT INTO write_lock (db_name, version, holder_kind, token, purpose, holder_pid, holder_start,
                                        acquired_at, heartbeat_at, last_activity_at)
                VALUES (current_database(), 1, ?, gen_random_uuid(), ?, ?, ?, now(), now(), now())
                """, kind, purpose, holder.pid(), holder.startEpochSecond());
    }

    private void running(String triggeredBy, ProcessIdentity owner) {
        jdbcTemplate.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, scope, started_at,
                                             owner_pid, owner_start, owner_db)
                VALUES (?, current_date, 'RUNNING', ?, 'FULL', now(), ?, ?, current_database())
                """, JOB, triggeredBy, owner.pid(), owner.startEpochSecond());
    }

    private Result run(String... command) throws Exception {
        return runOn(db, command);
    }

    private Result runOn(String database, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(List.of("bash")));
        builder.command().addAll(List.of(command));
        builder.environment().put("HOME", home.toString());
        builder.environment().put("DB_NAME", database);
        builder.environment().remove(WriteIdentity.SESSION_ENV);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("90초 안에 안 끝났다: " + builder.command());
        }
        return new Result(process.exitValue(), output);
    }

    private static ProcessIdentity waitForIdentity(Process process) throws InterruptedException {
        // exec 전의 bash와 exec 뒤의 sleep은 pid가 같고 시작 초도 같다 — 명령줄이 바뀔 때까지만 기다린다.
        for (int i = 0; i < 50; i++) {
            String command = process.toHandle().info().commandLine().orElse("");
            if (command.contains("app.jar") || process.toHandle().info().command().orElse("").endsWith("sleep")) {
                break;
            }
            Thread.sleep(100);
        }
        return ProcessIdentity.of(process.toHandle()).orElseThrow();
    }

    private record Result(int exit, String output) {
    }
}
