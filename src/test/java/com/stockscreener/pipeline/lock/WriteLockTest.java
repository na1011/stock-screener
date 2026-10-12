package com.stockscreener.pipeline.lock;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 쓰기 잠금의 획득 CAS · 사망 술어 · 세션 · 대기(ADR-0175 · 잠금 v5 드라이런 「시험으로 고정」).
 *
 * <p>「다른 JVM」은 실제로 살아 있는 자식 프로세스({@code sleep})의 신원으로 만든다 — 사망 술어가 진짜 프로세스 표를 보게.
 * 죽은 보유자는 내 pid에 시작 초를 하나 어긋낸 신원이다(pid 재사용과 같은 꼴).
 *
 * <p>잠금 행은 시험 DB의 하나뿐인 행이라 시험마다 비우고 시작·끝낸다. 가드 스위치는 켜지 않는다 — 여기는 획득 규칙만 잰다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class WriteLockTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    private final List<WriteLock> locks = new ArrayList<>();
    private final List<AlertEvent> alerts = new ArrayList<>();
    private Process other;
    private ProcessIdentity otherJvm;
    private final ProcessIdentity me = ProcessIdentity.current();

    @BeforeEach
    void setUp() throws IOException {
        jdbcTemplate.update("DELETE FROM write_lock");
        other = new ProcessBuilder("sleep", "600").start();
        otherJvm = ProcessIdentity.of(other.toHandle()).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        locks.forEach(WriteLock::destroy);
        other.destroyForcibly();
        jdbcTemplate.update("DELETE FROM write_lock");
    }

    @Test
    @DisplayName("행이 없는 표에서 쥐고, 반납하면 비지만 행은 남는다 — 대기 표지가 보유자와 따로 산다")
    void acquiresOnAnEmptyTableAndReleases() {
        WriteLock lock = lock(me, null);

        try (WriteLock.Token token = lock.acquire("t:first")) {
            assertThat(row().heldBy(me)).isTrue();
            assertThat(row().purpose()).isEqualTo("t:first");
        }
        LockRow after = row();
        assertThat(after.version()).isNotNull();
        assertThat(after.held()).isFalse();
    }

    @Test
    @DisplayName("산 다른 JVM이 쥐고 있으면 거절하고 보유자를 싣는다")
    void liveOtherHolderRefuses() {
        lock(otherJvm, null).acquire("t:other");

        assertThatThrownBy(() -> lock(me, null).acquire("t:me"))
                .isInstanceOf(WriteLockHeldException.class)
                .satisfies(e -> {
                    WriteLock.Holder holder = ((WriteLockHeldException) e).holder();
                    assertThat(holder.pid()).isEqualTo(otherJvm.pid());
                    assertThat(holder.purpose()).isEqualTo("t:other");
                });
        assertThat(row().heldBy(otherJvm)).isTrue();
    }

    @Test
    @DisplayName("보유 JVM이 죽었으면(같은 pid · 다른 시작 초) 넘겨받는다 — 박동이 낡았다는 것만으로는 안 넘겨받는다")
    void deadHolderIsTakenOver() {
        ProcessIdentity recycled = new ProcessIdentity(me.pid(), me.startEpochSecond() - 1);
        lock(recycled, null).acquire("t:dead");
        jdbcTemplate.update("UPDATE write_lock SET heartbeat_at = now() - interval '3 days'");

        lock(me, null).acquire("t:me");
        assertThat(row().heldBy(me)).isTrue();

        // 박동이 사흘 낡아도 프로세스가 살아 있으면 안 넘겨받는다(수면 — 잠금 v1 반증).
        jdbcTemplate.update("DELETE FROM write_lock");
        lock(otherJvm, null).acquire("t:asleep");
        jdbcTemplate.update("UPDATE write_lock SET heartbeat_at = now() - interval '3 days'");
        assertThatThrownBy(() -> lock(me, null).acquire("t:me")).isInstanceOf(WriteLockHeldException.class);
    }

    @Test
    @DisplayName("자기 누수 회수: 내 신원 행인데 내 레지스트리에 산 토큰이 없으면 한 번에 쥔다(잠금 v4 반증 L1-3 · devtools 재시작)")
    void reclaimsItsOwnLeakedRow() {
        // 반납에 실패하고 사라진 토큰 — DB 행만 내 신원으로 남았다.
        new WriteLockStore(url(), user(), password(), "t").acquireJvm(null, LockRow.HolderKind.WEB,
                java.util.UUID.randomUUID(), "t:leaked", me);

        WriteLock restarted = lock(me, null);
        try (WriteLock.Token token = restarted.acquire("t:cron")) {
            assertThat(row().purpose()).isEqualTo("t:cron");
            // 같은 JVM 안의 둘째 바깥 호출은 즉시 거절 — 회수 갈래는 산 토큰이 없을 때만이다.
            assertThatThrownBy(() -> restarted.acquire("t:admin")).isInstanceOf(WriteLockHeldException.class);
        }
    }

    @Test
    @DisplayName("크론도 자기 누수 행을 한 틱 안에 회수한다 — 기다리지 않는다(L1-3 · 풀 1 박동이 못 돌아 풀리지 않는 교착이던 꼴)")
    void cronReclaimsItsOwnLeakedRowWithoutWaiting() {
        new WriteLockStore(url(), user(), password(), "t").acquireJvm(null, LockRow.HolderKind.WEB,
                java.util.UUID.randomUUID(), "cron:runDailyUpdate", me);
        MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 10, 13, 14, 0, 0, 0, KST).toInstant());
        WriteLock restarted = lock(me, null, clock, duration -> {
            throw new AssertionError("자기 누수 행 앞에서 기다렸다 — 회수 갈래가 없다");
        });

        try (WriteLock.Token token = restarted.awaitForCron("runCorporateActionChain").orElseThrow()) {
            assertThat(row().purpose()).isEqualTo("cron:runCorporateActionChain");
        }
        assertThat(alerts).as("기다림 알림 없음").isEmpty();
    }

    @Test
    @DisplayName("CAS: 같은 관측 version으로 둘이 쥐면 하나만 이긴다 · 행이 없을 때도 INSERT 하나만 이긴다")
    void casLetsOnlyOneWin() {
        WriteLockStore store = new WriteLockStore(url(), user(), password(), "t");
        assertThat(store.acquireJvm(null, LockRow.HolderKind.WEB, java.util.UUID.randomUUID(), "a", me)).isTrue();
        assertThat(store.acquireJvm(null, LockRow.HolderKind.WEB, java.util.UUID.randomUUID(), "b", me)).isFalse();

        long observed = store.read().version();
        assertThat(store.acquireJvm(observed, LockRow.HolderKind.WEB, java.util.UUID.randomUUID(), "c", me)).isTrue();
        assertThat(store.acquireJvm(observed, LockRow.HolderKind.WEB, java.util.UUID.randomUUID(), "d", me)).isFalse();
        assertThat(store.read().purpose()).isEqualTo("c");
    }

    @Test
    @DisplayName("세션 만료 = 산 구성원 0 ∧ TTL 경과 — 실행 중인 exec가 구성원이면 TTL이 지나도 안 넘겨받는다(L1-2)")
    void sessionExpiresOnlyWithoutLiveMembers() {
        beginSession("abc", "{}", Duration.ofMinutes(30));
        assertThatThrownBy(() -> lock(me, null).acquire("t:me")).as("TTL 안").isInstanceOf(WriteLockHeldException.class);

        jdbcTemplate.update("DELETE FROM write_lock");
        beginSession("abc", "{exec:" + otherJvm.memberSuffix() + "}", Duration.ofMinutes(600));
        assertThatThrownBy(() -> lock(me, null).acquire("t:me")).as("긴 exec 도중").isInstanceOf(WriteLockHeldException.class);

        jdbcTemplate.update("DELETE FROM write_lock");
        ProcessIdentity deadExec = new ProcessIdentity(me.pid(), me.startEpochSecond() - 1);
        beginSession("abc", "{exec:" + deadExec.memberSuffix() + "}", WriteLock.SESSION_TTL.plusMinutes(1));
        lock(me, null).acquire("t:me");
        assertThat(row().heldBy(me)).isTrue();
        assertThat(alerts).extracting(AlertEvent::title).contains("쓰기 세션이 만료돼 잠금을 넘겨받았다");
    }

    @Test
    @DisplayName("합류도 CAS: 세션에 산 JVM이 이미 있으면 둘째 합류는 거절 — 앞 JVM이 나가면 합류한다(L1-1)")
    void secondJoinIsRefusedWhileTheFirstLives() {
        beginSession("s1", "{}", Duration.ZERO);
        WriteLock first = lock(otherJvm, "s1");
        first.joinSession("job:a");
        assertThat(row().members()).containsExactly("jvm:" + otherJvm.memberSuffix());

        WriteLock second = lock(me, "s1");
        assertThatThrownBy(() -> second.joinSession("job:b"))
                .isInstanceOf(WriteLockHeldException.class)
                .hasMessageContaining("하나씩");

        other.destroyForcibly();
        waitUntilDead(otherJvm);
        second.joinSession("job:b");
        assertThat(row().members()).containsExactly("jvm:" + me.memberSuffix());
    }

    @Test
    @DisplayName("세션이 없거나 만료됐으면 합류를 거절한다 — 다시 begin(그 사이 남의 쓰기가 끼었을 수 있다)")
    void joinRequiresALiveSession() {
        assertThatThrownBy(() -> lock(me, "nope").joinSession("job:x")).isInstanceOf(WriteLockHeldException.class);

        beginSession("old", "{}", WriteLock.SESSION_TTL.plusMinutes(5));
        assertThatThrownBy(() -> lock(me, "old").joinSession("job:x"))
                .isInstanceOf(WriteLockHeldException.class)
                .hasMessageContaining("만료");
    }

    @Test
    @DisplayName("산 대기 표지가 남의 것이면 비어 있어도 안 쥔다(세션 밖 새 획득 거절) — 표지 주인이 죽었으면 쥔다")
    void liveWaiterKeepsItsTurn() {
        jdbcTemplate.update("INSERT INTO write_lock (db_name, version, waiter_pid, waiter_start, waiter_purpose, "
                + "waiter_since) VALUES (current_database(), 1, ?, ?, 'cron:runDailyUpdate', now())",
                otherJvm.pid(), otherJvm.startEpochSecond());

        assertThatThrownBy(() -> lock(me, null).acquire("admin:x"))
                .isInstanceOf(WriteLockHeldException.class)
                .satisfies(e -> assertThat(((WriteLockHeldException) e).holder().waiting()).isTrue());

        other.destroyForcibly();
        waitUntilDead(otherJvm);
        lock(me, null).acquire("admin:x");
        assertThat(row().heldBy(me)).isTrue();
        assertThat(row().hasWaiter()).as("쥐는 쪽이 죽은 표지를 걷는다").isFalse();
    }

    @Test
    @DisplayName("크론은 기다린다 — 기다리는 동안 표지를 남기고 첫 대기에 알린 뒤, 보유자가 반납하면 쥔다")
    void cronWaitsWithAMarkerThenAcquires() {
        WriteLock holder = lock(otherJvm, null);
        WriteLock.Token held = holder.acquire("job:manual");
        MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 10, 13, 14, 0, 0, 0, KST).toInstant());
        List<LockRow> seenWhileWaiting = new ArrayList<>();
        WriteLock cron = lock(me, null, clock, duration -> {
            seenWhileWaiting.add(row());
            held.close();
        });

        Optional<WriteLock.Token> token = cron.awaitForCron("runCorporateActionChain");

        assertThat(token).isPresent();
        assertThat(seenWhileWaiting).hasSize(1);
        assertThat(seenWhileWaiting.get(0).waiterIs(me)).isTrue();
        assertThat(seenWhileWaiting.get(0).waiterPurpose()).isEqualTo("cron:runCorporateActionChain");
        assertThat(row().heldBy(me)).isTrue();
        assertThat(row().hasWaiter()).isFalse();
        assertThat(alerts).extracting(AlertEvent::title).containsExactly("쓰기 크론이 잠금을 기다린다: runCorporateActionChain");
    }

    @Test
    @DisplayName("상한 없이 기다린다 — 자정을 넘겨도 풀리는 즉시 쥐고, 기다리는 동안 첫 대기와 매시간 알린다(ADR-0175)")
    void cronWaitsWithoutADeadlineAndRemindsHourly() {
        WriteLock.Token held = lock(otherJvm, null).acquire("job:long");
        MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 10, 13, 22, 59, 40, 0, KST).toInstant());
        Instant release = ZonedDateTime.of(2026, 10, 14, 1, 5, 0, 0, KST).toInstant();
        WriteLock cron = lock(me, null, clock, duration -> {
            clock.advance(Duration.ofMinutes(5));
            if (clock.instant().isAfter(release)) {
                held.close();
            }
        });

        Optional<WriteLock.Token> token = cron.awaitForCron("reconcileIndicators");

        assertThat(token).as("23:00을 넘겨도 건너뛰지 않는다").isPresent();
        assertThat(clock.instant()).isAfter(release);
        assertThat(row().heldBy(me)).isTrue();
        assertThat(row().hasWaiter()).isFalse();
        assertThat(alerts).extracting(AlertEvent::title).containsExactly(
                "쓰기 크론이 잠금을 기다린다: reconcileIndicators",
                "쓰기 크론이 아직 잠금을 기다린다: reconcileIndicators",
                "쓰기 크론이 아직 잠금을 기다린다: reconcileIndicators");
        token.get().close();
    }

    @Test
    @DisplayName("종료 신호면 그만둔다 — 인터럽트·컨텍스트 닫힘 둘 다 빈 값 · 표지 걷음 · 그 뒤 이 JVM의 획득이 안 막힌다")
    void shutdownEndsTheWait() {
        WriteLock.Token held = lock(otherJvm, null).acquire("job:long");
        WriteLock interrupted = lock(me, null, Clock.systemUTC(), duration -> {
            throw new InterruptedException("시험");
        });
        assertThat(interrupted.awaitForCron("runDailyUpdate")).isEmpty();
        Thread.interrupted(); // 되살린 인터럽트 표지를 걷는다 — 남기면 이 시험 스레드의 뒤 JDBC 호출이 흔들린다
        assertThat(row().hasWaiter()).isFalse();

        WriteLock[] closing = new WriteLock[1];
        closing[0] = lock(me, null, Clock.systemUTC(), duration ->
                closing[0].onContextClosed(new ContextClosedEvent(applicationContext)));
        assertThat(closing[0].awaitForCron("runDailyUpdate")).isEmpty();
        assertThat(row().hasWaiter()).isFalse();

        held.close();
        try (WriteLock.Token admin = interrupted.acquire("admin:after")) {
            assertThat(row().heldBy(me)).as("크론 대기 표지가 남았으면 「크론이 먼저 돈다」로 거절된다").isTrue();
        }
    }

    @Test
    @DisplayName("대기 도중 잠금 표가 실패하면 표지를 걷고 ERROR로 알린 뒤 던진다 — 다음 크론까지 남의 획득을 막는 래치가 안 남는다")
    void storeFailureWhileWaitingClearsTheMarker() {
        WriteLock.Token held = lock(otherJvm, null).acquire("job:long");
        MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 10, 13, 14, 0, 0, 0, KST).toInstant());
        WriteLock cron = lock(new FlakyStore(me, 2), me, clock, clock::advance);

        assertThatThrownBy(() -> cron.awaitForCron("runCorporateActionChain"))
                .isInstanceOf(WriteLockStore.WriteLockStoreException.class);

        assertThat(row().hasWaiter()).as("DB 대기 표지").isFalse();
        assertThat(alerts).extracting(AlertEvent::title).contains("쓰기 크론이 잠금을 기다리다 실패했다: runCorporateActionChain");
        held.close();
        try (WriteLock.Token admin = cron.acquire("admin:after")) {
            assertThat(row().heldBy(me)).as("JVM 안 대기 표지(cronWaiting)가 남았으면 거절된다").isTrue();
        }
    }

    @Test
    @DisplayName("박동과 반납이 겹쳐 0행이어도 그 토큰이 이미 닫혔으면 상실이 아니다(거짓 ERROR 없음)")
    void heartbeatRacingAReleaseIsNotALoss() {
        RacingStore store = new RacingStore(me);
        WriteLock lock = lock(store, me, Clock.systemUTC(), duration -> { });
        WriteLock.Token token = lock.acquire("admin:short");
        store.beforeHeartbeat = token::close;

        lock.beat();

        assertThat(alerts).extracting(AlertEvent::title).doesNotContain("쓰기 잠금을 잃었다");
    }

    @Test
    @DisplayName("상실 알림은 토큰마다 한 번 — 새 토큰을 쥐면 래치를 걷어 그 토큰의 상실도 알린다")
    void lossIsReportedOncePerToken() {
        WriteLock lock = lock(me, null);
        WriteLock.Token first = lock.acquire("admin:first");
        jdbcTemplate.update("UPDATE write_lock SET token = gen_random_uuid()");
        lock.beat();
        lock.beat();
        first.close();

        jdbcTemplate.update("DELETE FROM write_lock");
        WriteLock.Token second = lock.acquire("admin:second");
        jdbcTemplate.update("UPDATE write_lock SET token = gen_random_uuid()");
        lock.beat();
        second.close();

        assertThat(alerts).extracting(AlertEvent::title).filteredOn("쓰기 잠금을 잃었다"::equals).hasSize(2);
    }

    @Test
    @DisplayName("같은 JVM의 관리자 잡이 쥐었으면 크론은 기다리고, 그 반납에 바로 깨어 쥔다 — 그 사이 남은 못 끼어든다")
    void cronWaitsForItsOwnJvmToken() {
        MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 10, 13, 13, 30, 0, 0, KST).toInstant());
        List<LockRow> seen = new ArrayList<>();
        WriteLock[] self = new WriteLock[1];
        WriteLock.Token[] admin = new WriteLock.Token[1];
        self[0] = lock(me, null, clock, duration -> {
            seen.add(row());
            admin[0].close();
            // 반납 직후 다른 JVM이 끼어들려 하면 표지 때문에 거절된다.
            assertThatThrownBy(() -> lock(otherJvm, null).acquire("job:sneak"))
                    .isInstanceOf(WriteLockHeldException.class);
        });
        admin[0] = self[0].acquire("admin:daily-update");

        WriteLock.Token token = self[0].awaitForCron("runDailyUpdate").orElseThrow();

        assertThat(seen.get(0).waiterIs(me)).isTrue();
        assertThat(token.purpose()).isEqualTo("cron:runDailyUpdate");
        assertThat(row().purpose()).isEqualTo("cron:runDailyUpdate");
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────

    private WriteLock lock(ProcessIdentity process, String session) {
        return lock(process, session, Clock.systemUTC(), duration -> {
            throw new AssertionError("이 시험은 기다리지 않는다");
        });
    }

    private WriteLock lock(ProcessIdentity process, String session, Clock clock, WriteLock.Pause pause) {
        WriteIdentity identity = new WriteIdentity(process, session);
        WriteLock lock = new WriteLock(identity,
                new WriteLockStore(url(), user(), password(), identity.applicationName()),
                alerts::add, clock, pause, null);
        lock.enable(LockRow.HolderKind.BOOTRUN);
        locks.add(lock);
        return lock;
    }

    private WriteLock lock(WriteLockStore store, ProcessIdentity process, Clock clock, WriteLock.Pause pause) {
        WriteLock lock = new WriteLock(new WriteIdentity(process, null), store, alerts::add, clock, pause, null);
        lock.enable(LockRow.HolderKind.BOOTRUN);
        locks.add(lock);
        return lock;
    }

    /** n번째 읽기에서 실패하는 잠금 표 — 대기 둘째 바퀴의 연결 실패. */
    private final class FlakyStore extends WriteLockStore {
        private final int failOn;
        private int reads;

        FlakyStore(ProcessIdentity process, int failOn) {
            super(url(), user(), password(), new WriteIdentity(process, null).applicationName());
            this.failOn = failOn;
        }

        @Override
        LockRow read() {
            if (++reads == failOn) {
                throw new WriteLockStoreException("시험: 잠금 표를 못 읽었다", new java.sql.SQLException("시험"));
            }
            return super.read();
        }
    }

    /** 박동 직전에 끼어드는 일(반납)을 흉내 낸다 — 박동은 모니터 밖이라 실제로 이렇게 겹친다. */
    private final class RacingStore extends WriteLockStore {
        private Runnable beforeHeartbeat = () -> { };

        RacingStore(ProcessIdentity process) {
            super(url(), user(), password(), new WriteIdentity(process, null).applicationName());
        }

        @Override
        boolean heartbeat(java.util.UUID token) {
            beforeHeartbeat.run();
            return super.heartbeat(token);
        }
    }

    private void beginSession(String id, String members, Duration idleFor) {
        jdbcTemplate.update("INSERT INTO write_lock (db_name, version, holder_kind, token, purpose, session_id, members, "
                        + "acquired_at, last_activity_at) VALUES (current_database(), 1, 'SESSION', gen_random_uuid(), ?, ?, "
                        + "?::text[], now(), now() - make_interval(secs => ?))",
                "session:" + id, id, members, idleFor.toSeconds());
    }

    private LockRow row() {
        return new WriteLockStore(url(), user(), password(), "t").read();
    }

    private static void waitUntilDead(ProcessIdentity identity) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (identity.isAlive()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("자식 프로세스가 10초 안에 안 죽었다: " + identity);
            }
            Thread.onSpinWait();
        }
    }

    private String url() {
        return environment.getProperty("spring.datasource.url");
    }

    private String user() {
        return environment.getProperty("spring.datasource.username");
    }

    private String password() {
        return environment.getProperty("spring.datasource.password");
    }

    /** 대기 사이마다 앞으로 미는 시계. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
