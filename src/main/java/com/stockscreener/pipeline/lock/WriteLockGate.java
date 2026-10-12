package com.stockscreener.pipeline.lock;

import com.stockscreener.pipeline.batch.BootstrapRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 기동 구간의 쓰기 잠금 — Flyway·시더·스위퍼가 쓰기 <b>전에</b> 쥔다(ADR-0175).
 *
 * <h2>순서</h2>
 * <p>{@link WriteLockOrdering}이 {@code flywayInitializer}·{@code entityManagerFactory}를 이 빈에 매단다 — 그래서 Flyway(마이그레이션의
 * DML)와 카탈로그 시더({@code ApplicationRunner})와 스위퍼({@code ApplicationReadyEvent})가 모두 이 토큰 안에서 쓴다. 이 빈은 잘못된
 * DB에 잠금 행을 쓰지 않게 DB 가드({@code BootstrapDatabaseGuard} · 시험의 {@code assertTestDatabase}) 뒤다 — 그 가드는 프로파일에
 * 따라 없으므로 <b>있을 때만</b> 매단다(잠금 v4 반증 L1-8).
 *
 * <h2>무엇을 쥐나</h2>
 * <ul>
 *   <li>웹 JVM: 기동 토큰 — {@code ApplicationReadyEvent}의 스위퍼 뒤에 반납한다. 이후 크론·관리자 launch·판정 {@code PUT}이 호출마다 쥔다.</li>
 *   <li>bootRun: 잡 한 판의 토큰 — 프로세스가 끝날 때 종료 처리기가 반납한다. {@link #SESSION_ENV}가 있으면 그 세션에 합류한다.</li>
 *   <li>읽기 전용 bootRun(보호 표 쓰기 0인 잡) · 읽기 전용 연결(백테스트 하네스): 아무것도 안 쥔다 — 쓰면 가드가 거절한다.</li>
 * </ul>
 *
 * <h2>표가 없으면</h2>
 * <p>{@code write_lock}이 아직 없으면(V66 전 DB) 진행하고 WARN을 남긴 뒤 <b>Flyway 직후 다시</b> 쥔다 — V66을 적용하는 그 한 번이다.
 * 판정은 Flyway 버전이 아니라 표의 존재로 한다(V66 앞 덤프로 되돌린 DB는 이력이 62인데 표가 있을 수 있다).
 *
 * <h2>거절</h2>
 * <p>MUST: bootRun도 웹도 {@code System.exit(1)}이다. 던지면 devtools가 러너를 {@code restartedMain}에서 돌려 gradle이 종료코드 0을
 * 낸다({@code BootstrapRunner} javadoc) — 거절이 성공으로 읽힌다. 시험 프로파일만 던진다. 문구 「쓰기 잠금 게이트가 기동을
 * 세운다」를 {@code ops/start.sh}가 로그에서 찾아 찍는다.
 */
@Component(WriteLockGate.BEAN)
public class WriteLockGate implements InitializingBean {

    static final String BEAN = "writeLockGate";

    static final String SESSION_ENV = WriteIdentity.SESSION_ENV;

    /** 거절 문구의 머리. {@code ops/start.sh}가 이 문구로 로그를 찾는다 — 바꾸면 거기도 바꾼다. */
    public static final String REFUSAL_PREFIX = "쓰기 잠금 게이트가 기동을 세운다";

    private static final Logger log = LoggerFactory.getLogger(WriteLockGate.class);

    private final WriteLock lock;
    private final DataSource dataSource;
    private final Environment environment;
    private final ObjectProvider<ApplicationArguments> arguments;
    private final ApplicationContext applicationContext;

    private boolean pendingAfterFlyway;
    private WriteLock.Token bootToken;

    public WriteLockGate(WriteLock lock, DataSource dataSource, Environment environment,
                         ObjectProvider<ApplicationArguments> arguments, ApplicationContext applicationContext) {
        this.lock = lock;
        this.dataSource = dataSource;
        this.environment = environment;
        this.arguments = arguments;
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        boolean bootstrap = environment.acceptsProfiles(Profiles.of("bootstrap"));
        if (readOnlyConnection()) {
            lock.disable("읽기 전용 연결");
            log.info("읽기 전용 연결이라 쓰기 잠금을 쥐지 않는다 — 쓰면 DB가 거절한다.");
            return;
        }
        String job = job();
        if (bootstrap && job != null && BootstrapRunner.isReadOnlyJob(job)) {
            lock.disable("읽기 전용 잡 " + job);
            log.info("읽기 전용 잡({})이라 쓰기 잠금을 쥐지 않는다 — 보호 표에 쓰면 가드가 거절한다.", job);
            return;
        }
        WriteIdentity identity = lock.identity();
        if (identity.inSession() && !identity.sessionValid()) {
            refuse(SESSION_ENV + "=「" + identity.rawSession() + "」의 꼴이 틀렸다 — [a-z0-9-]{1,24}만 쓴다"
                    + "(application_name 63바이트에서 신원이 조용히 잘린다).");
            return;
        }
        if (identity.inSession() && !bootstrap) {
            refuse("웹 프로파일이 " + SESSION_ENV + "를 물려받았다 — 앱 JVM이 세션에 합류하면 세션이 영영 안 만료된다."
                    + " ops/start.sh로 띄우거나(env -u로 걷는다) 그 변수를 걷고 다시 띄운다.");
            return;
        }
        assertApplicationNameRoundTrip(identity.applicationName());
        lock.enable(bootstrap ? LockRow.HolderKind.BOOTRUN : LockRow.HolderKind.WEB);
        SpringApplication.getShutdownHandlers().add(lock::releaseAtExit);
        if (!lock.store().tableExists()) {
            pendingAfterFlyway = true;
            log.warn("write_lock 표가 아직 없다(V66 전 DB) — 잠금 없이 Flyway까지 가고, Flyway 직후 다시 쥔다.");
            return;
        }
        acquireBootToken(bootstrap, job);
    }

    /** Flyway가 끝난 직후({@link WriteLockAfterFlyway}). 기동 때 표가 없었을 때만 할 일이 있다. */
    void afterFlyway() {
        if (!pendingAfterFlyway) {
            return;
        }
        pendingAfterFlyway = false;
        if (!lock.store().tableExists()) {
            lock.disable("Flyway 뒤에도 write_lock이 없다");
            log.warn("Flyway 뒤에도 write_lock 표가 없다 — 이 JVM은 쓰기 잠금 없이 돈다(V66이 이 마이그레이션 경로에 없다).");
            return;
        }
        acquireBootToken(environment.acceptsProfiles(Profiles.of("bootstrap")), job());
    }

    private void acquireBootToken(boolean bootstrap, String job) {
        String purpose = bootstrap ? "job:" + (job == null ? "?" : job) : "boot";
        try {
            bootToken = lock.identity().inSession() ? lock.joinSession(purpose) : lock.acquire(purpose);
            log.info("쓰기 잠금을 쥐었다. 신원={} 목적={}", lock.identity().applicationName(), purpose);
        } catch (WriteLockHeldException | WriteLockStore.WriteLockStoreException e) {
            refuse(e.getMessage());
        }
    }

    /**
     * 웹 JVM의 기동 토큰을 스위퍼 뒤에 반납한다. 스위퍼({@code BatchExecutionSweeper.sweepOnStartup})는 보호 표
     * ({@code batch_execution})에 쓰므로 그보다 늦어야 한다 — 이쪽은 맨 뒤({@code LOWEST_PRECEDENCE}), 스위퍼는 맨 앞
     * ({@code HIGHEST_PRECEDENCE})으로 못 박는다. 한쪽만 정하면 다른 쪽의 기본값(LOWEST)과 같아 클래스 경로 순서에 맡겨진다.
     * {@code WriteLockStartupOrderTest}가 실제 컨텍스트에서 그 순서를 잰다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void releaseBootTokenWhenReady(ApplicationReadyEvent event) {
        if (event.getApplicationContext() != applicationContext || bootToken == null) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("bootstrap"))) {
            return; // bootRun은 잡 한 판 내내 쥔다 — 종료 처리기가 반납한다.
        }
        bootToken.close();
        bootToken = null;
        log.info("기동 구간의 쓰기 잠금을 반납했다 — 이제 크론·관리자 호출이 호출마다 쥔다.");
    }

    private boolean readOnlyConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isReadOnly();
        } catch (SQLException e) {
            throw new IllegalStateException("쓰기 잠금 게이트가 DB에 닿지 못했다: " + e.getMessage(), e);
        }
    }

    /**
     * 연결의 {@code application_name}이 정한 신원 그대로인가. 63바이트에서 잘리거나 비ASCII가 이스케이프되면 가드가 이 JVM을
     * 보유자로 못 알아본다 — 그때는 쓰기가 전부 거절되므로 여기서 먼저 세운다.
     */
    private void assertApplicationNameRoundTrip(String expected) {
        String actual;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_setting('application_name')")) {
            rs.next();
            actual = rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("쓰기 잠금 게이트가 DB에 닿지 못했다: " + e.getMessage(), e);
        }
        if (!expected.equals(actual)) {
            refuse("연결의 application_name이 「" + actual + "」로 왔다 — 기대 「" + expected + "」. 데이터소스가 신원을 안 실었다"
                    + "(ApplicationNameDataSourcePostProcessor) · 잘렸다.");
        }
    }

    private String job() {
        ApplicationArguments args = arguments.getIfAvailable();
        if (args == null) {
            return null;
        }
        List<String> values = args.getOptionValues("job");
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private void refuse(String reason) {
        String line = REFUSAL_PREFIX + ": " + reason;
        if (environment.acceptsProfiles(Profiles.of("test | backtest"))) {
            throw new IllegalStateException(line);
        }
        log.error(line);
        System.exit(1);
    }
}
