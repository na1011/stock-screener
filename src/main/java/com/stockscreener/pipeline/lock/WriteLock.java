package com.stockscreener.pipeline.lock;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JVM을 건너는 운영 DB 쓰기 배타 잠금 — JVM 쪽 절반(ADR-0175). DB 쪽 절반은 V66의 가드 트리거다.
 *
 * <h2>두 층</h2>
 * <ul>
 *   <li><b>JVM 안</b>: 바깥 호출 토큰이 한 번에 하나다(쓰기 크론 발화 · 관리자 launch · 원장 판정 {@code PUT} · 웹 기동 구간 ·
 *       bootRun 한 판). 같은 JVM의 둘째 바깥 호출은 즉시 거절되고, 크론만 기다린다.</li>
 *   <li><b>JVM 사이</b>: 첫 토큰이 {@code write_lock} 행을 이 JVM 신원으로 쥐고 마지막 토큰이 반납한다. 다른 JVM·세션이 쥐고 있으면
 *       크론은 기다리고 나머지는 거절된다.</li>
 * </ul>
 *
 * <h2>획득 술어 — 관측한 행이 아래 중 하나일 때만 CAS로 쥔다</h2>
 * <ul>
 *   <li>비었음(행 없음 · 반납됨)</li>
 *   <li>보유 JVM이 죽음 — pid 부재 ∨ 시작 초 불일치({@link ProcessIdentity})</li>
 *   <li>세션 만료 — 산 구성원(합류 JVM · 실행 중인 {@code exec} 래퍼) 0 ∧ 마지막 활동에서 {@link #SESSION_TTL} 경과</li>
 *   <li><b>보유 신원 = 나 ∧ 내 레지스트리에 산 토큰 0</b> — 반납에 실패한 자기 누수 회수(잠금 v4 반증 L1-3 · devtools 재시작)</li>
 * </ul>
 * <p>그리고 산 대기 표지가 남의 것이면 쥐지 않는다(크론이 차례를 잃지 않게 — 세션 밖 새 획득 거절).
 *
 * <h2>크론의 대기 — 상한이 없다</h2>
 * <p>쓰기 크론은 잠금이 풀리는 즉시 돈다. 기다리는 동안 첫 대기와 그 뒤 매시간 보유자·기다린 시간을 알린다. 그만두는 것은
 * 종료 신호(컨텍스트 닫힘 · 인터럽트)뿐이다. 날짜 상한(건너뜀)을 두지 않는 이유는 ADR-0175 §결정에 있다 — 건너뜀은 그 몫을 다른
 * 실행이 메운다는 가정에 기대는데 주 1회 리밸런싱에는 그 가정이 없고, 멈춘 보유자는 사망 술어·세션 TTL이 푼다.
 * <p>대기에서 어떤 예외로 빠져도 대기 표지(JVM 안 {@code cronWaiting} · DB {@code waiter_*})는 걷는다 — 남으면 다음 크론까지
 * 관리자 launch·판정 {@code PUT}·bootRun·{@code write-session begin}이 전부 막힌다.
 *
 * <h2>반납 시점</h2>
 * <p>토큰을 닫으면 그 자리에서 반납한다 — 단 컨텍스트가 닫히기 시작했으면 반납을 <b>종료 처리기</b>({@link #releaseAtExit})로 미룬다.
 * 종료 마감({@code BatchExecutions.onContextClosed})과 끝나 가는 배치 스레드가 그 뒤에도 보호 표에 쓰기 때문이다.
 */
@Component
public class WriteLock implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WriteLock.class);

    /**
     * 세션이 구성원 없이 버틸 수 있는 시간. 재료(60일 MANUAL 연쇄의 실행 사이 틈 n=602 · p50 1.2분 · p90 206.7분 · 10~60분 64 ·
     * 60~240분 49 — 2026-10-07 측정)에 꺾이는 점이 없어 값이 판정을 못 가른다. 크론 대기에 상한이 없으므로 잊힌 세션이 사슬을 붙잡는
     * 시간이 곧 이 값이다 — 그래서 짧은 쪽으로 정했다. 실행 중인 {@code exec}는 구성원이라 이 시간에 안 걸린다.
     * MUST: 셸의 만료 경계({@code ops/lib.sh}의 {@code write_session_idle_expired} — 유휴 초 ≥ TTL)와 같다.
     * MUST: {@code ops/write-session.sh}의 {@code TTL_MINUTES}와 같다({@code WriteSessionScriptTest}가 대조한다).
     */
    public static final Duration SESSION_TTL = Duration.ofMinutes(60);

    /** 크론이 다른 JVM·세션의 반납을 다시 보러 가는 간격. 같은 JVM 안의 반납은 즉시 깨운다. */
    static final Duration CRON_POLL = Duration.ofSeconds(15);

    static final Duration HEARTBEAT = Duration.ofSeconds(30);

    /** 대기가 이어지는 동안 다시 알리는 간격. */
    static final Duration CRON_WAIT_REMINDER = Duration.ofHours(1);

    private static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");

    /** CAS가 경합으로 연달아 지면 거절로 친다 — 그 사이 누군가 계속 행을 바꾸고 있다. */
    private static final int CAS_ATTEMPTS = 5;

    private final WriteIdentity identity;
    private final WriteLockStore store;
    private final AlertPort alertPort;
    private final Clock clock;
    private final Pause pause;
    private final ApplicationContext applicationContext;

    private final Object monitor = new Object();
    private Mode mode = Mode.UNDECIDED;
    private String disabledReason;
    private LockRow.HolderKind kind;
    private String joinedSession;
    private Token live;
    private boolean cronWaiting;
    private boolean shuttingDown;
    private UUID pendingRelease;
    private boolean lossReported;
    private ScheduledExecutorService heartbeat;

    /**
     * MUST: 알림 포트를 지연으로 받는다 — 게이트가 이 빈을 JPA·JdbcTemplate보다 먼저 만드는데, 알림 포트는 메일 수신자를 찾으려
     * JdbcTemplate을 물고 있어 바로 받으면 순환이다.
     */
    @Autowired
    public WriteLock(Environment environment, ObjectProvider<AlertPort> alertPort, ApplicationContext applicationContext) {
        this(WriteIdentity.resolve(environment), storeFor(environment, WriteIdentity.resolve(environment)),
                event -> alertPort.getObject().send(event), Clock.systemUTC(), null, applicationContext);
    }

    WriteLock(WriteIdentity identity, WriteLockStore store, AlertPort alertPort, Clock clock, Pause pause,
              ApplicationContext applicationContext) {
        this.identity = identity;
        this.store = store;
        this.alertPort = alertPort;
        this.clock = clock;
        this.pause = pause != null ? pause : this::waitOnMonitor;
        this.applicationContext = applicationContext;
    }

    /** DB 잠금을 아예 안 쓰는 잠금 — 단위 시험과 쓰지 않는 JVM용. 토큰은 받되 아무것도 안 쥔다. */
    public static WriteLock inactive(String reason) {
        WriteLock lock = new WriteLock(new WriteIdentity(ProcessIdentity.current(), null), null,
                event -> { }, Clock.systemUTC(), null, null);
        lock.disable(reason);
        return lock;
    }

    private static WriteLockStore storeFor(Environment environment, WriteIdentity identity) {
        return new WriteLockStore(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"),
                identity.applicationName());
    }

    // ── 게이트가 정하는 것 ─────────────────────────────────────────────────────────

    WriteIdentity identity() {
        return identity;
    }

    WriteLockStore store() {
        return store;
    }

    /** 이 JVM은 DB 잠금을 쓴다. {@code kind}는 WEB 또는 BOOTRUN. */
    void enable(LockRow.HolderKind kind) {
        synchronized (monitor) {
            this.kind = kind;
            this.mode = Mode.ENABLED;
            startHeartbeat();
        }
    }

    /** 이 JVM은 DB 잠금을 안 쓴다(읽기 전용 연결 · 읽기 전용 잡 · 잠금 표 없음). */
    void disable(String reason) {
        synchronized (monitor) {
            this.mode = Mode.DISABLED;
            this.disabledReason = reason;
        }
    }

    public boolean enabled() {
        synchronized (monitor) {
            return mode == Mode.ENABLED;
        }
    }

    // ── 바깥 호출 ──────────────────────────────────────────────────────────────────

    /**
     * 즉시 쥔다. 못 쥐면 {@link WriteLockHeldException} — 관리자 launch · 원장 판정 · 웹 기동 · bootRun.
     *
     * @param purpose 무엇을 위해 쥐는가. 잠금 표와 거절 문구에 그대로 실린다
     */
    public Token acquire(String purpose) {
        synchronized (monitor) {
            requireDecided();
            if (mode == Mode.DISABLED) {
                return Token.inactive(this, purpose);
            }
            if (live != null) {
                throw new WriteLockHeldException("이 JVM에서 「" + live.purpose + "」이(가) 쓰기 잠금을 쥐고 있다 — 끝난 뒤 다시 한다.",
                        new Holder(kind.name(), identity.process().pid(), null, live.purpose, null, false));
            }
            if (cronWaiting) {
                throw new WriteLockHeldException("이 JVM의 쓰기 크론이 잠금 차례를 기다린다 — 크론이 먼저 돈다.",
                        new Holder(kind.name(), identity.process().pid(), null, "대기 중인 쓰기 크론", null, true));
            }
            if (joinedSession != null) {
                throw new WriteLockHeldException("세션 「" + joinedSession + "」에 합류한 JVM은 바깥 호출 토큰을 하나만 쥔다.", null);
            }
            Attempt attempt = tryAcquire(purpose);
            if (attempt.token() != null) {
                live = attempt.token();
                return live;
            }
            throw attempt.refusal();
        }
    }

    /**
     * 쓰기 크론의 획득 — 비면 쥐고, 차 있으면 표지를 남기고 <b>풀릴 때까지</b> 기다린다(상한 없음 · ADR-0175). 첫 대기와 그 뒤
     * 매시간 알린다.
     *
     * <p>빈 값은 종료 신호(컨텍스트 닫힘 · 인터럽트)로 그만둔 것뿐이다 — 내려가는 중이라 알림이 없다. 그 밖의 예외는 표지를 걷고
     * ERROR로 알린 뒤 다시 던진다(그 발화는 안 돌았다 — 삼키지 않는다).
     */
    public Optional<Token> awaitForCron(String cron) {
        String purpose = "cron:" + cron;
        Instant since = clock.instant();
        Instant nextReminder = null;
        Holder lastBlocker = null;
        boolean waiting = false;
        boolean acquired = false;
        try {
            while (true) {
                synchronized (monitor) {
                    requireDecided();
                    if (mode == Mode.DISABLED) {
                        return Optional.of(Token.inactive(this, purpose));
                    }
                    if (shuttingDown) {
                        return Optional.empty();
                    }
                    LockRow observed = null;
                    if (live == null && joinedSession == null) {
                        Attempt attempt = tryAcquire(purpose);
                        if (attempt.token() != null) {
                            acquired = true;
                            cronWaiting = false;
                            live = attempt.token();
                            if (waiting) {
                                log.info("쓰기 크론이 기다린 끝에 잠금을 쥐었다. 크론={} 기다림={}", cron,
                                        Duration.between(since, clock.instant()));
                            }
                            return Optional.of(live);
                        }
                        lastBlocker = attempt.refusal().holder();
                        observed = attempt.observed();
                    } else {
                        lastBlocker = live == null ? null
                                : new Holder(kind.name(), identity.process().pid(), null, live.purpose, null, false);
                    }
                    waiting = true;
                    cronWaiting = true;
                    markMyWaiter(observed, purpose);
                }
                Instant now = clock.instant();
                if (nextReminder == null) {
                    alertWaiting(cron, lastBlocker);
                    nextReminder = now.plus(CRON_WAIT_REMINDER);
                } else if (!now.isBefore(nextReminder)) {
                    alertStillWaiting(cron, lastBlocker, Duration.between(since, now));
                    nextReminder = now.plus(CRON_WAIT_REMINDER);
                }
                pause.pause(CRON_POLL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("쓰기 크론이 잠금을 기다리다 종료로 그만뒀다. 크론={}", cron);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("쓰기 크론이 잠금을 기다리다 실패했다 — 이 발화는 돌지 않았다. 크론={}", cron, e);
            alertPort.send(AlertEvent.toOperator(AlertSeverity.ERROR, "쓰기 크론이 잠금을 기다리다 실패했다: " + cron,
                    "잠금 표를 읽거나 쓰다 실패해 이 크론을 돌리지 않았다(" + e.getMessage() + "). 실행 행은 없다 — 다음 발화가 다시"
                            + " 기다린다. 놓친 몫(무거운 잡의 연체 · 사슬 중간 상태)은 손으로 확인한다.",
                    "write-lock-wait-failed:" + cron + ":" + clock.instant().atZone(KR_ZONE).toLocalDate()));
            throw e;
        } finally {
            if (!acquired && waiting) {
                synchronized (monitor) {
                    cronWaiting = false;
                    clearMyWaiter();
                }
            }
        }
    }

    /**
     * 세션에 합류한다 — {@code ops/write-session.sh exec}가 띄운 bootRun. 합류도 CAS다: 세션에 산 JVM이 이미 있으면 거절한다
     * (세션 안 bootRun은 하나씩 · 잠금 v4 반증 L1-1). 세션이 없거나 만료됐으면 거절한다(다시 {@code begin}).
     */
    Token joinSession(String purpose) {
        String sessionId = identity.sessionId();
        String me = "jvm:" + identity.process().memberSuffix();
        synchronized (monitor) {
            requireDecided();
            for (int i = 0; i < CAS_ATTEMPTS; i++) {
                LockRow row = store.read();
                if (!row.heldBySession(sessionId)) {
                    throw new WriteLockHeldException("세션 「" + sessionId + "」이 쓰기 잠금을 쥐고 있지 않다 — "
                            + "ops/write-session.sh begin " + sessionId + " 뒤 exec로 띄운다. 지금 보유: " + describe(row),
                            holderOf(row, false));
                }
                if (sessionExpired(row)) {
                    throw new WriteLockHeldException("세션 「" + sessionId + "」이 만료됐다(구성원 없이 " + SESSION_TTL.toMinutes()
                            + "분) — 그 사이 남의 쓰기가 끼었을 수 있으니 begin부터 다시 한다.", holderOf(row, false));
                }
                List<String> kept = new ArrayList<>();
                for (String member : row.members()) {
                    if (member.startsWith("jvm:") && memberAlive(member)) {
                        throw new WriteLockHeldException("세션 「" + sessionId + "」에 이미 산 JVM(" + member
                                + ")이 합류해 있다 — 세션 안 bootRun은 하나씩 돈다.", holderOf(row, false));
                    }
                    if (memberAlive(member)) {
                        kept.add(member);
                    }
                }
                kept.add(me);
                if (store.replaceMembers(row.version(), sessionId, kept)) {
                    lossReported = false;
                    joinedSession = sessionId;
                    live = new Token(this, null, purpose);
                    log.info("세션 {}에 합류했다. 구성원={}", sessionId, kept);
                    return live;
                }
            }
            throw new WriteLockHeldException("세션 「" + sessionId + "」 합류 CAS가 " + CAS_ATTEMPTS + "번 연달아 졌다.", null);
        }
    }

    /**
     * 지금 {@link #acquire}를 부르면 거절할 쪽 — 화면의 jobs 목록이 버튼을 잠그고 보유자를 보인다(잠금 결정 12). 쥘 수 있으면 빈
     * 값이다: 비었음 · 보유자가 죽었음 · 세션 만료 · 내 누수 행. 판정은 {@link #acquire}와 같은 술어({@link #freedBecause})라 화면과
     * 실제 거절이 안 갈린다. 쓰지 않는 JVM이면 빈 값이다.
     */
    public Optional<Holder> blockingHolder() {
        synchronized (monitor) {
            if (mode != Mode.ENABLED) {
                return Optional.empty();
            }
            if (live != null) {
                return Optional.of(new Holder(kind.name(), identity.process().pid(), null, live.purpose, null, false));
            }
            if (cronWaiting) {
                return Optional.of(new Holder(kind.name(), identity.process().pid(), null, "대기 중인 쓰기 크론", null, true));
            }
            LockRow row = store.read();
            ProcessIdentity me = identity.process();
            if (freedBecause(row, me) == null) {
                return Optional.of(holderOf(row, false));
            }
            if (row.hasWaiter() && !row.waiterIs(me) && ProcessIdentity.isAlive(row.waiterPid(), row.waiterStart())) {
                return Optional.of(holderOf(row, true));
            }
            return Optional.empty();
        }
    }

    // ── 획득 판정 ──────────────────────────────────────────────────────────────────

    /** monitor 안에서만 부른다. */
    private Attempt tryAcquire(String purpose) {
        ProcessIdentity me = identity.process();
        LockRow row = null;
        for (int i = 0; i < CAS_ATTEMPTS; i++) {
            row = store.read();
            String freedBecause = freedBecause(row, me);
            if (freedBecause == null) {
                return Attempt.refused(row, new WriteLockHeldException(
                        "쓰기 잠금을 " + describe(row) + "이(가) 쥐고 있다 — 끝난 뒤 다시 한다(ADR-0175).", holderOf(row, false)));
            }
            if (row.hasWaiter() && !row.waiterIs(me) && ProcessIdentity.isAlive(row.waiterPid(), row.waiterStart())) {
                return Attempt.refused(row, new WriteLockHeldException(
                        "쓰기 크론(" + row.waiterPurpose() + ")이 잠금 차례를 기다린다 — 그 크론이 먼저 돈다.", holderOf(row, true)));
            }
            UUID token = UUID.randomUUID();
            if (store.acquireJvm(row.version(), kind, token, purpose, me)) {
                lossReported = false; // 상실 알림은 토큰마다 한 번 — 새 토큰의 상실은 다시 알린다
                if (!freedBecause.isEmpty()) {
                    log.warn("쓰기 잠금을 넘겨받았다 — {}. 목적={}", freedBecause, purpose);
                }
                if (freedBecause.startsWith("세션")) {
                    alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "쓰기 세션이 만료돼 잠금을 넘겨받았다",
                            freedBecause + " — 「" + purpose + "」이(가) 쥐었다. 세션을 끝내지 않고 둔 손 작업이 있었다면 그 뒤의"
                                    + " exec는 거절된다(다시 begin).", "write-lock-session-expired:" + row.sessionId()));
                }
                return Attempt.acquired(row, new Token(this, token, purpose));
            }
        }
        return Attempt.refused(row, new WriteLockHeldException(
                "쓰기 잠금 CAS가 " + CAS_ATTEMPTS + "번 연달아 졌다 — 누군가 계속 행을 바꾸고 있다. 지금: " + describe(row),
                holderOf(row, false)));
    }

    /**
     * 관측한 행을 쥘 수 있으면 그 사유(비었으면 빈 문자열), 아니면 {@code null}.
     */
    private String freedBecause(LockRow row, ProcessIdentity me) {
        if (!row.held()) {
            return "";
        }
        if (row.kind() == LockRow.HolderKind.SESSION) {
            return sessionExpired(row) ? "세션 「" + row.sessionId() + "」 만료(산 구성원 0 · 마지막 활동 "
                    + row.lastActivityAt() + ")" : null;
        }
        if (row.heldBy(me)) {
            // monitor 안이고 live가 비어 있을 때만 여기 온다 — 내 신원 행인데 산 토큰이 없다.
            return "내 신원 행인데 산 토큰이 없다(반납 실패 · 재시작) — 회수";
        }
        if (!ProcessIdentity.isAlive(row.holderPid(), row.holderStart())) {
            return "보유 JVM pid " + row.holderPid() + "(시작 " + Instant.ofEpochSecond(row.holderStart()) + ")가 죽었다";
        }
        return null;
    }

    private boolean sessionExpired(LockRow row) {
        for (String member : row.members()) {
            if (memberAlive(member)) {
                return false;
            }
        }
        Instant last = row.lastActivityAt() != null ? row.lastActivityAt() : row.acquiredAt();
        return last == null || !last.plus(SESSION_TTL).isAfter(row.dbNow());
    }

    /** {@code jvm:<pid>:<시작초>} · {@code exec:<pid>:<시작초>}. 꼴이 틀린 구성원은 산 것으로 친다(빼앗지 않는 쪽). */
    static boolean memberAlive(String member) {
        String[] parts = member.split(":");
        if (parts.length != 3) {
            log.warn("세션 구성원 꼴이 틀렸다: {} — 산 것으로 친다.", member);
            return true;
        }
        try {
            return ProcessIdentity.isAlive(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            log.warn("세션 구성원 꼴이 틀렸다: {} — 산 것으로 친다.", member);
            return true;
        }
    }

    // ── 반납 ───────────────────────────────────────────────────────────────────────

    private void release(Token token) {
        synchronized (monitor) {
            if (token.closed) {
                return;
            }
            token.closed = true;
            if (live != token) {
                return;
            }
            live = null;
            monitor.notifyAll();
            if (shuttingDown || joinedSession != null || token.id == null) {
                // 종료 중이면 종료 처리기가, 세션 합류면 퇴장이 맨 마지막에 한다.
                return;
            }
            try {
                if (!store.release(token.id)) {
                    log.warn("반납하려던 쓰기 잠금이 이미 내 것이 아니다 — 누가 넘겨받았다. 목적={}", token.purpose);
                }
            } catch (RuntimeException e) {
                pendingRelease = token.id;
                log.warn("쓰기 잠금 반납에 실패했다 — 박동이 다시 시도하고, 그 전에 이 JVM의 다음 획득이 회수한다. 목적={}",
                        token.purpose, e);
            }
        }
    }

    /**
     * 컨텍스트가 닫히기 시작했다 — 이제부터 토큰을 닫아도 반납하지 않는다. 반납은 {@link #releaseAtExit()}가 맨 마지막에 한다.
     */
    @EventListener
    void onContextClosed(ContextClosedEvent event) {
        if (applicationContext != null && event.getApplicationContext() != applicationContext) {
            return;
        }
        synchronized (monitor) {
            shuttingDown = true;
            monitor.notifyAll();
        }
    }

    /**
     * 종료 처리기 — 컨텍스트가 닫히고 빈이 파괴된 <b>뒤</b>에 Spring Boot가 부른다({@code SpringApplication.getShutdownHandlers()}).
     * 그래서 자기 연결로 반납한다(풀은 이미 닫혔다). 실패하면 남은 행은 다음 획득자가 사망 술어로 넘겨받는다.
     */
    void releaseAtExit() {
        synchronized (monitor) {
            shuttingDown = true;
            if (mode != Mode.ENABLED) {
                return;
            }
            live = null;
            try {
                if (joinedSession != null) {
                    store.removeMember(joinedSession, "jvm:" + identity.process().memberSuffix());
                    log.info("세션 {}에서 나왔다.", joinedSession);
                    return;
                }
                LockRow row = store.read();
                if (row.heldBy(identity.process())) {
                    store.release(row.token());
                    log.info("쓰기 잠금을 반납했다(종료). 목적={}", row.purpose());
                }
                if (row.waiterIs(identity.process())) {
                    store.clearWaiter(identity.process());
                }
            } catch (RuntimeException e) {
                log.warn("종료 반납에 실패했다 — 남은 행은 다음 획득자가 사망 술어로 넘겨받는다.", e);
            }
        }
    }

    @Override
    public void destroy() {
        synchronized (monitor) {
            if (heartbeat != null) {
                heartbeat.shutdownNow();
            }
        }
    }

    // ── 박동 ───────────────────────────────────────────────────────────────────────

    /**
     * 박동은 <b>전용 데몬 스레드</b>다 — 스케줄러 풀(1)에 두면 크론이 잠금을 기다리는 동안 박동이 못 돌아 자기 누수 행을
     * 못 걷는다(잠금 v4 반증 L1-3). 보유 중이 아니고 밀린 반납도 없으면 DB에 안 닿는다(박동 커넥션은 보유 중에만).
     */
    private void startHeartbeat() {
        if (heartbeat != null) {
            return;
        }
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "write-lock-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(this::beatSafely, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void beatSafely() {
        try {
            beat();
        } catch (RuntimeException e) {
            // 박동 하나가 실패해도 다음 박동은 돌아야 한다 — 예외가 새면 ScheduledExecutorService가 이후 실행을 조용히 멈춘다.
            log.warn("쓰기 잠금 박동이 실패했다 — 다음 박동에서 다시 본다.", e);
        }
    }

    void beat() {
        UUID holding;
        UUID pending;
        String session;
        synchronized (monitor) {
            if (mode != Mode.ENABLED || shuttingDown) {
                return;
            }
            holding = live == null ? null : live.id;
            pending = pendingRelease;
            session = joinedSession;
        }
        if (pending != null && store.release(pending)) {
            log.info("밀린 쓰기 잠금 반납을 마쳤다.");
        }
        if (pending != null) {
            synchronized (monitor) {
                if (pendingRelease == pending) {
                    pendingRelease = null;
                }
            }
        }
        if (session != null) {
            LockRow row = store.read();
            if (!row.heldBySession(session)
                    || !row.members().contains("jvm:" + identity.process().memberSuffix())) {
                reportLoss("세션 「" + session + "」이 더 이상 이 JVM을 구성원으로 두지 않는다. 지금: " + describe(row));
            }
        } else if (holding != null && !store.heartbeat(holding)) {
            // 박동은 모니터 밖이라 그 사이 토큰이 정상 반납됐을 수 있다 — 그 토큰이 아직 산 것일 때만 상실이다.
            boolean stillLive;
            synchronized (monitor) {
                stillLive = live != null && holding.equals(live.id) && !live.closed;
            }
            if (stillLive) {
                reportLoss("이 JVM의 토큰이 더 이상 보유자가 아니다. 지금: " + describe(store.read()));
            }
        }
    }

    private void reportLoss(String detail) {
        synchronized (monitor) {
            if (lossReported) {
                return;
            }
            lossReported = true;
        }
        log.error("쓰기 잠금을 잃었다 — {} 이 JVM의 다음 보호 표 쓰기는 가드가 거절한다(가드가 켜진 DB).", detail);
        alertPort.send(AlertEvent.toOperator(AlertSeverity.ERROR, "쓰기 잠금을 잃었다",
                detail + " — 도는 잡의 쓰기가 가드에 거절돼 실패로 끝난다. 누가 넘겨받았는지 write_lock을 본다.",
                "write-lock-lost:" + identity.applicationName()));
    }

    // ── 대기 표지·알림 ─────────────────────────────────────────────────────────────

    private void markMyWaiter(LockRow observed, String purpose) {
        try {
            LockRow row = observed != null ? observed : store.read();
            if (row.version() == null || (row.hasWaiter() && !row.waiterIs(identity.process())
                    && ProcessIdentity.isAlive(row.waiterPid(), row.waiterStart()))) {
                return;
            }
            if (row.waiterIs(identity.process())) {
                return;
            }
            store.markWaiter(row.version(), identity.process(), purpose);
        } catch (RuntimeException e) {
            log.warn("대기 표지를 남기지 못했다 — 다음 확인에서 다시 남긴다.", e);
        }
    }

    private void clearMyWaiter() {
        if (store == null) {
            return;
        }
        try {
            store.clearWaiter(identity.process());
        } catch (RuntimeException e) {
            log.warn("대기 표지를 걷지 못했다 — 표지 주인이 죽으면 다음 획득자가 무시한다.", e);
        }
    }

    private void alertWaiting(String cron, Holder blocker) {
        String who = blocker == null ? "알 수 없음" : blocker.describe();
        log.warn("쓰기 크론이 잠금을 기다린다. 크론={} 보유={}", cron, who);
        alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "쓰기 크론이 잠금을 기다린다: " + cron,
                who + "이(가) 쓰기 잠금을 쥐고 있다. 그것이 끝나면 크론이 이어 돈다(상한 없음 · 매시간 다시 알린다).",
                "write-lock-wait:" + cron + ":" + clock.instant().atZone(KR_ZONE).toLocalDate()));
    }

    private void alertStillWaiting(String cron, Holder blocker, Duration waited) {
        String who = blocker == null ? "알 수 없음" : blocker.describe();
        log.warn("쓰기 크론이 아직 잠금을 기다린다. 크론={} 기다림={} 보유={}", cron, waited, who);
        alertPort.send(AlertEvent.toOperator(AlertSeverity.WARNING, "쓰기 크론이 아직 잠금을 기다린다: " + cron,
                who + "이(가) 쓰기 잠금을 쥐고 있어 " + waited.toHours() + "시간째 기다린다. 그것이 끝나면 크론이 이어 돈다 — 상한은"
                        + " 없다. 이 JVM의 다른 크론은 그동안 줄을 서 늦게 돈다(스케줄러 풀 1).",
                "write-lock-wait:" + cron + ":" + clock.instant().atZone(KR_ZONE).toLocalDate() + ":" + waited.toHours() + "h"));
    }

    private void waitOnMonitor(Duration duration) throws InterruptedException {
        synchronized (monitor) {
            if (!shuttingDown) {
                monitor.wait(duration.toMillis());
            }
        }
    }

    private void requireDecided() {
        if (mode == Mode.UNDECIDED) {
            throw new IllegalStateException("쓰기 잠금 게이트가 아직 이 JVM의 잠금을 정하지 않았다 — WriteLockGate가 먼저 돌아야 한다.");
        }
    }

    // ── 표현 ───────────────────────────────────────────────────────────────────────

    static String describe(LockRow row) {
        if (!row.held()) {
            return row.hasWaiter() ? "없음(대기: " + row.waiterPurpose() + " pid " + row.waiterPid() + ")" : "없음";
        }
        return holderOf(row, false).describe();
    }

    static Holder holderOf(LockRow row, boolean waiting) {
        if (waiting) {
            return new Holder("WAITER", row.waiterPid(), null, row.waiterPurpose(), row.waiterSince(), true);
        }
        if (!row.held()) {
            return new Holder(null, null, null, null, null, row.hasWaiter());
        }
        return new Holder(row.kind().name(), row.holderPid(), row.sessionId(), row.purpose(), row.acquiredAt(), false);
    }

    /**
     * 잠금을 쥔(또는 그 차례를 기다리는) 쪽. 409 문제 응답과 jobs 목록이 그대로 싣는다.
     *
     * @param kind      WEB · BOOTRUN · SESSION · WAITER(크론 대기) · {@code null}(비었음)
     * @param waiting   거절 사유가 보유가 아니라 대기 중인 쓰기 크론인가
     */
    public record Holder(String kind, Long pid, String sessionId, String purpose, Instant since, boolean waiting) {

        public String describe() {
            if (kind == null) {
                return "없음";
            }
            if ("SESSION".equals(kind)) {
                return "손 세션 「" + sessionId + "」";
            }
            return kind + " pid " + pid + " 「" + purpose + "」" + (since == null ? "" : " (" + since + "부터)");
        }
    }

    /** 바깥 호출 토큰. 닫으면 반납한다(종료 중이면 종료 처리기가). 두 번 닫아도 된다. */
    public static final class Token implements AutoCloseable {

        private final WriteLock owner;
        private final UUID id;
        private final String purpose;
        private boolean closed;

        private Token(WriteLock owner, UUID id, String purpose) {
            this.owner = owner;
            this.id = id;
            this.purpose = purpose;
        }

        private static Token inactive(WriteLock owner, String purpose) {
            Token token = new Token(owner, null, purpose);
            token.closed = true;
            return token;
        }

        public String purpose() {
            return purpose;
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }

    private record Attempt(LockRow observed, Token token, WriteLockHeldException refusal) {
        static Attempt acquired(LockRow observed, Token token) {
            return new Attempt(observed, token, null);
        }

        static Attempt refused(LockRow observed, WriteLockHeldException refusal) {
            return new Attempt(observed, null, refusal);
        }
    }

    /** 대기 사이의 쉼. 시험이 가상 시계를 밀 수 있게 갈아끼운다. */
    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    private enum Mode {
        UNDECIDED, ENABLED, DISABLED
    }
}
