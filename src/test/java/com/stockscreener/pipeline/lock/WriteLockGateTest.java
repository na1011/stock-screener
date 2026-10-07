package com.stockscreener.pipeline.lock;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기동 게이트의 갈래(ADR-0175) — 웹 프로파일의 세션 변수 거절(잠금 v4 반증 L1-6b) · 세션 id 꼴 · 읽기 전용 잡 · 산 보유자 거절.
 *
 * <p>시험 프로파일이라 거절이 {@code System.exit}이 아니라 예외다. 실제 bootRun의 종료코드 1은 빈 시험 DB에 붙은 bootRun으로
 * 따로 쟀다(구현 보고 · 3초 만에 rc 1 · 교착 없음).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class WriteLockGateTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<WriteLock> locks = new ArrayList<>();
    private Process other;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM write_lock");
    }

    @AfterEach
    void tearDown() {
        locks.forEach(WriteLock::destroy);
        if (other != null) {
            other.destroyForcibly();
        }
        jdbcTemplate.update("DELETE FROM write_lock");
    }

    @Test
    @DisplayName("웹 프로파일이 SS_WRITE_SESSION을 물려받으면 기동을 세운다 — 앱 JVM이 세션에 합류하면 세션이 영영 안 만료된다")
    void webProfileRefusesASessionVariable() {
        MockEnvironment env = env("test").withProperty(WriteIdentity.SESSION_ENV, "apply");

        assertThatThrownBy(() -> gate(env, new String[0]).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(WriteLockGate.REFUSAL_PREFIX)
                .hasMessageContaining("웹 프로파일");
    }

    @Test
    @DisplayName("세션 id 꼴이 틀리면 bootRun도 세운다 — application_name이 63바이트에서 조용히 잘린다")
    void malformedSessionIdIsRefused() {
        MockEnvironment env = env("test", "bootstrap").withProperty(WriteIdentity.SESSION_ENV, "Apply_Window");

        assertThatThrownBy(() -> gate(env, new String[] {"--job=backfill"}).afterPropertiesSet())
                .hasMessageContaining("꼴이 틀렸다");
    }

    @Test
    @DisplayName("읽기 전용 잡(보호 표 쓰기 0)은 잠금을 안 쥔다 — 산 보유자가 있어도 뜬다 · 판정 잡은 거절된다")
    void readOnlyJobsDoNotTakeTheLock() throws IOException {
        holdByAnotherLiveProcess();

        WriteLockGate review = gate(env("test", "bootstrap"), new String[] {"--job=corporate-action-review"});
        review.afterPropertiesSet();
        assertThat(lockOf(review).enabled()).isFalse();

        assertThatThrownBy(() -> gate(env("test", "bootstrap"), new String[] {"--job=corporate-action-judge"})
                .afterPropertiesSet())
                .hasMessageStartingWith(WriteLockGate.REFUSAL_PREFIX)
                .hasMessageContaining("BOOTRUN pid " + other.pid());
    }

    @Test
    @DisplayName("bootRun은 잡 한 판 내내 쥔다 — 기동 토큰을 ApplicationReadyEvent에 반납하지 않는다")
    void bootRunHoldsForTheWholeJob() {
        WriteLockGate gate = gate(env("test", "bootstrap"), new String[] {"--job=governance-derive"});
        gate.afterPropertiesSet();

        assertThat(jdbcTemplate.queryForObject("SELECT purpose FROM write_lock", String.class))
                .isEqualTo("job:governance-derive");
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────────

    private final java.util.Map<WriteLockGate, WriteLock> lockByGate = new java.util.IdentityHashMap<>();

    private WriteLockGate gate(MockEnvironment env, String[] args) {
        WriteIdentity identity = WriteIdentity.resolve(env);
        WriteLock lock = new WriteLock(identity, new WriteLockStore(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"), identity.applicationName()),
                event -> { }, Clock.systemUTC(), duration -> { }, null);
        locks.add(lock);
        ApplicationArguments arguments = new DefaultApplicationArguments(args);
        WriteLockGate gate = new WriteLockGate(lock, new RenamingDataSource(dataSource, identity.applicationName()), env,
                provider(arguments), applicationContext);
        lockByGate.put(gate, lock);
        return gate;
    }

    private WriteLock lockOf(WriteLockGate gate) {
        return lockByGate.get(gate);
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private void holdByAnotherLiveProcess() throws IOException {
        other = new ProcessBuilder("sleep", "600").start();
        ProcessIdentity identity = ProcessIdentity.of(other.toHandle()).orElseThrow();
        jdbcTemplate.update("INSERT INTO write_lock (db_name, version, holder_kind, token, purpose, holder_pid, "
                + "holder_start) VALUES (current_database(), 1, 'BOOTRUN', gen_random_uuid(), 'job:other', ?, ?)",
                identity.pid(), identity.startEpochSecond());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    /**
     * 시험 컨텍스트의 풀은 이 JVM 신원을 싣는다 — 게이트의 왕복 단언은 그 신원을 기대하므로, 시험이 만든 신원(세션 등)과 맞게
     * 연결마다 {@code application_name}을 바꿔 준다.
     */
    private static final class RenamingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        private final String applicationName;

        RenamingDataSource(DataSource target, String applicationName) {
            super(target);
            this.applicationName = applicationName;
        }

        /** 풀로 돌려줄 때 원래 이름으로 되돌린다 — 안 그러면 이 이름이 풀에 남아 다른 시험의 신원이 된다. */
        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            java.sql.Connection connection = super.getConnection();
            String original = connection.getClientInfo("ApplicationName");
            connection.setClientInfo("ApplicationName", applicationName);
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.Connection.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                    (proxy, method, methodArgs) -> {
                        if ("close".equals(method.getName())) {
                            connection.setClientInfo("ApplicationName", original);
                        }
                        try {
                            return method.invoke(connection, methodArgs);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
