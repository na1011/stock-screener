package com.stockscreener.watchlist;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엣지 트리거의 네 가지 전이와 "사용자당 한 통"을 검증한다.
 *
 * <p>여기서 틀리면 증상이 <b>알림이 오지 않는 것</b>이거나 <b>매일 오는 것</b>인데, 둘 다
 * 며칠이 지나야 알아차린다. 그래서 상태 전이를 하나씩 못박는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, WatchlistAlertRunnerTest.RecordingAlertConfig.class})
@Transactional
class WatchlistAlertRunnerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);
    /** 판정 기준일은 시장마다 다르다. 이 픽스처의 종목은 전부 KOSPI다. */
    private static final Map<String, LocalDate> AS_OF_BY_MARKET = Map.of("KOSPI", AS_OF);

    @Autowired
    private WatchlistAlertRunner runner;

    @Autowired
    private WatchlistStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingAlertPort alertPort;

    private long userId;
    private long otherUserId;
    private long stockId;
    private long screenId;

    @BeforeEach
    void seed() {
        alertPort.received.clear();
        userId = insertUser("sub-runner-a", "runner-a@example.com");
        otherUserId = insertUser("sub-runner-b", "runner-b@example.com");
        stockId = insertStock("910001", "판정대상");
        // PER < 10 하나짜리 화면. 값은 아래에서 심는다.
        screenId = insertScreen(userId, "판정용 조건", "PER", "LT", new BigDecimal("10"));
    }

    @Test
    @DisplayName("거짓→참에 알린다")
    void notifiesOnRisingEdge() {
        long itemId = watch(userId, stockId, screenId);
        putIndicator(stockId, "PER", new BigDecimal("8"));

        WatchlistAlertRunner.Result result = runner.run(AS_OF_BY_MARKET);

        assertThat(result.triggered()).isEqualTo(1);
        assertThat(result.notifiedUsers()).isEqualTo(1);
        assertThat(alertPort.received).hasSize(1);
        assertThat(alertPort.received.get(0).recipientUserId()).isEqualTo(userId);
        assertThat(alertPort.received.get(0).body()).contains("판정대상", "판정용 조건");
        assertThat(lastSatisfied(itemId)).isTrue();
        assertThat(lastNotifiedAt(itemId)).isNotNull();
    }

    @Test
    @DisplayName("참→참에는 침묵한다 — 매일 같은 알림이 오면 곧 아무도 읽지 않는다")
    void staysSilentWhileStillSatisfied() {
        long itemId = watch(userId, stockId, screenId);
        putIndicator(stockId, "PER", new BigDecimal("8"));

        runner.run(AS_OF_BY_MARKET);
        alertPort.received.clear();

        WatchlistAlertRunner.Result second = runner.run(AS_OF_BY_MARKET);

        assertThat(second.evaluated()).isEqualTo(1);
        assertThat(second.triggered()).isZero();
        assertThat(alertPort.received).isEmpty();
        assertThat(lastSatisfied(itemId)).isTrue();
    }

    @Test
    @DisplayName("참→거짓→참이면 다시 알린다 — 그것이 엣지 트리거의 요점이다")
    void notifiesAgainAfterFalling() {
        long itemId = watch(userId, stockId, screenId);
        putIndicator(stockId, "PER", new BigDecimal("8"));
        runner.run(AS_OF_BY_MARKET);

        putIndicator(stockId, "PER", new BigDecimal("20"));
        runner.run(AS_OF_BY_MARKET);
        assertThat(lastSatisfied(itemId)).isFalse();
        alertPort.received.clear();

        putIndicator(stockId, "PER", new BigDecimal("7"));
        runner.run(AS_OF_BY_MARKET);

        assertThat(alertPort.received).hasSize(1);
    }

    @Test
    @DisplayName("지표가 없으면 알리지 않는다 — 판정할 수 없는 것을 충족으로 읽으면 근거 없는 알림이 나간다")
    void doesNotNotifyWhenIndicatorMissing() {
        long itemId = watch(userId, stockId, screenId);
        // 지표를 심지 않는다.

        WatchlistAlertRunner.Result result = runner.run(AS_OF_BY_MARKET);

        assertThat(result.triggered()).isZero();
        assertThat(alertPort.received).isEmpty();
        assertThat(lastSatisfied(itemId)).isFalse();
    }

    @Test
    @DisplayName("여러 종목이 동시에 충족돼도 사용자당 한 통이다")
    void oneAlertPerUser() {
        long second = insertStock("910002", "판정대상2");
        watch(userId, stockId, screenId);
        watch(userId, second, screenId);
        putIndicator(stockId, "PER", new BigDecimal("8"));
        putIndicator(second, "PER", new BigDecimal("9"));

        WatchlistAlertRunner.Result result = runner.run(AS_OF_BY_MARKET);

        assertThat(result.triggered()).isEqualTo(2);
        assertThat(result.notifiedUsers()).isEqualTo(1);
        assertThat(alertPort.received).hasSize(1);
        assertThat(alertPort.received.get(0).title()).contains("2개");
        assertThat(alertPort.received.get(0).body()).contains("판정대상", "판정대상2");
    }

    @Test
    @DisplayName("사용자가 다르면 각자 받는다")
    void notifiesEachUserSeparately() {
        long otherScreen = insertScreen(otherUserId, "남의 조건", "PER", "LT", new BigDecimal("10"));
        watch(userId, stockId, screenId);
        watch(otherUserId, stockId, otherScreen);
        putIndicator(stockId, "PER", new BigDecimal("8"));

        WatchlistAlertRunner.Result result = runner.run(AS_OF_BY_MARKET);

        assertThat(result.notifiedUsers()).isEqualTo(2);
        assertThat(alertPort.received).extracting(AlertEvent::recipientUserId)
                .containsExactlyInAnyOrder(userId, otherUserId);
    }

    @Test
    @DisplayName("IMPORTANT — SCORING 화면도 FILTER로 판정한다. 아니면 그 화면을 건 종목이 매일 알림을 받는다")
    void scoringScreenIsEvaluatedAsFilter() {
        long scoringScreen = insertScreen(userId, "점수 조건", "PER", "LT", new BigDecimal("10"));
        jdbcTemplate.update("UPDATE screen SET evaluation_mode = 'SCORING' WHERE id = ?", scoringScreen);
        jdbcTemplate.update("UPDATE screen_condition SET weight = 1 WHERE group_id = "
                + "(SELECT id FROM condition_group WHERE screen_id = ?)", scoringScreen);

        watch(userId, stockId, scoringScreen);
        // 조건을 충족하지 못하는 값. SCORING으로 판정하면 included가 참이라 알림이 나간다.
        putIndicator(stockId, "PER", new BigDecimal("50"));

        WatchlistAlertRunner.Result result = runner.run(AS_OF_BY_MARKET);

        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.triggered()).isZero();
        assertThat(alertPort.received).isEmpty();
    }

    @Test
    @DisplayName("알림 조건이 없는 항목은 판정 대상이 아니다")
    void itemsWithoutScreenAreNotEvaluated() {
        watch(userId, stockId, null);
        putIndicator(stockId, "PER", new BigDecimal("8"));

        WatchlistAlertRunner.Result result = runner.run(AS_OF_BY_MARKET);

        assertThat(result.evaluated()).isZero();
        assertThat(alertPort.received).isEmpty();
    }

    @Test
    @DisplayName("비활성 화면은 판정하지 않는다 — 폐기된 기준으로 계속 알리면 안 된다")
    void inactiveScreenIsSkipped() {
        watch(userId, stockId, screenId);
        putIndicator(stockId, "PER", new BigDecimal("8"));
        jdbcTemplate.update("UPDATE screen SET is_active = false WHERE id = ?", screenId);

        WatchlistAlertRunner.Result result = runner.run(AS_OF_BY_MARKET);

        assertThat(result.evaluated()).isZero();
        assertThat(result.failedScreens()).isZero();
        assertThat(alertPort.received).isEmpty();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private long watch(long user, long stock, Long screen) {
        return store.create(user, stock, screen, null).id();
    }

    private void putIndicator(long stock, String code, BigDecimal value) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (stock_id, trade_date, indicator_code) DO UPDATE SET value = EXCLUDED.value
                """, stock, AS_OF, code, value);
    }

    private Boolean lastSatisfied(long itemId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_satisfied FROM watchlist_item WHERE id = ?", Boolean.class, itemId);
    }

    private Object lastNotifiedAt(long itemId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_notified_at FROM watchlist_item WHERE id = ?", Object.class, itemId);
    }

    private long insertUser(String sub, String email) {
        jdbcTemplate.update("""
                INSERT INTO app_user (email, name, provider, provider_user_id, is_admin, is_active)
                VALUES (?, '테스트', 'GOOGLE', ?, false, true) ON CONFLICT DO NOTHING
                """, email, sub);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE provider_user_id = ?", Long.class, sub);
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active)
                VALUES ('KOSPI', ?, ?, true)
                ON CONFLICT (market_code, ticker) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class, ticker, name);
    }

    private long insertScreen(long owner, String name, String indicatorCode, String operator,
                              BigDecimal literal) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO screen (name, screen_type, evaluation_mode, owner_user_id, is_active)
                VALUES (?, 'CUSTOM', 'FILTER', ?, true)
                RETURNING id
                """, Long.class, name, owner);
        Long groupId = jdbcTemplate.queryForObject("""
                INSERT INTO condition_group (screen_id, logical_operator) VALUES (?, 'AND')
                RETURNING id
                """, Long.class, id);
        jdbcTemplate.update("""
                INSERT INTO screen_condition (group_id, indicator_code, operator, operand_type, literal_value)
                VALUES (?, ?, ?, 'LITERAL', ?)
                """, groupId, indicatorCode, operator, literal);
        return id;
    }

    /**
     * 통지를 가로채는 포트.
     *
     * <p>MUST: {@code @Primary}여야 한다. {@code AlertConfig}의
     * {@code @ConditionalOnMissingBean}에만 기대면 조건 평가가 테스트 설정 등록보다 먼저
     * 일어나 <b>실제 포트가 주입되고 이 목록은 영원히 비어 있다.</b> 증상이 "알림이 안 왔다"라
     * 판정 로직의 결함처럼 보인다 — 실제로 여기서 한 번 걸렸다.
     * {@code PipelineEndToEndTest}가 같은 이유로 같은 방식을 쓴다.
     */
    static class RecordingAlertPort implements AlertPort {
        private final List<AlertEvent> received = new ArrayList<>();

        @Override
        public void send(AlertEvent event) {
            received.add(event);
        }
    }

    @TestConfiguration
    static class RecordingAlertConfig {
        @Bean
        @Primary
        RecordingAlertPort recordingAlertPort() {
            return new RecordingAlertPort();
        }
    }
}
