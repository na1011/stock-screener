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
    @DisplayName("발화한 날 23:00이 넘으면 건너뛴다 — 빈 값 · 표지 걷음 · 알림 · 보유자는 그대로")
    void cronSkipsAtTheDeadline() {
        lock(otherJvm, null).acquire("job:long");
        MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 10, 13, 22, 59, 40, 0, KST).toInstant());
        WriteLock cron = lock(me, null, clock, clock::advance);

        Optional<WriteLock.Token> token = cron.awaitForCron("reconcileIndicators");

        assertThat(token).isEmpty();
        assertThat(row().heldBy(otherJvm)).isTrue();
        assertThat(row().hasWaiter()).isFalse();
        assertThat(alerts).extracting(AlertEvent::title)
                .containsExactly("쓰기 크론이 잠금을 기다린다: reconcileIndicators", "쓰기 크론을 건너뛰었다: reconcileIndicators");
    }

    @Test
    @DisplayName("23:00 뒤에 발화했으면(수면) 비었을 때만 돈다 — 차 있으면 기다리지 않고 건너뛴다")
    void lateFireRunsOnlyWhenFree() {
        MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 10, 13, 23, 40, 0, 0, KST).toInstant());
        WriteLock cron = lock(me, null, clock, duration -> {
            throw new AssertionError("23:00 뒤 발화는 기다리지 않는다");
        });
        try (WriteLock.Token token = cron.awaitForCron("runDailyUpdate").orElseThrow()) {
            assertThat(row().heldBy(me)).isTrue();
        }

        jdbcTemplate.update("DELETE FROM write_lock");
        lock(otherJvm, null).acquire("job:late");
        assertThat(cron.awaitForCron("runDailyUpdate")).isEmpty();
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
