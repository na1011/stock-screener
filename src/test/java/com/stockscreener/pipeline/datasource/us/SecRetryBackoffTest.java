package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC 받기의 같은 실행 안 재시도({@link SecFilingClient#fetch}) — 대기 · 연속 소진 차단 · 다시 묻지 않는 실패. 보충의 받기와
 * companyfacts가 이 한 자리를 쓴다(v11 반증: 따로 짜인 둘이 인터럽트 처리에서 갈렸다).
 */
class SecRetryBackoffTest {

    private final List<Long> pauses = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("운영 대기는 2초 · 10초다 — 다시 묻는 횟수와 길이가 같다")
    void standardWaitsMatchTheRetries() {
        assertThat(SecFilingClient.Backoff.STANDARD_MILLIS).containsExactly(2_000L, 10_000L);
        assertThat(SecFilingClient.Backoff.STANDARD_MILLIS).hasSize(SecFilingClient.TRANSIENT_RETRIES);
    }

    @Test
    @DisplayName("전송 실패는 대기를 두고 다시 묻고, 끝까지 실패하면 TRANSIENT다")
    void waitsBetweenAttempts() {
        SecFilingClient.Backoff backoff = recording();

        assertThatThrownBy(() -> fetch(backoff, failing()))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
        assertThat(calls).hasValue(1 + SecFilingClient.TRANSIENT_RETRIES);
        assertThat(pauses).containsExactly(2_000L, 10_000L);
    }

    @Test
    @DisplayName("다시 묻기를 다 쓴 콜이 연달아 셋이면 열린다 — 열린 동안은 기다리지 않고 한 번만 묻고, 응답 하나가 다시 닫는다")
    void opensAfterConsecutiveExhaustionAndClosesOnAnAnswer() throws Exception {
        SecFilingClient.Backoff backoff = recording();
        for (int i = 0; i < SecFilingClient.Backoff.OPEN_AFTER; i++) {
            assertThatThrownBy(() -> fetch(backoff, failing())).isInstanceOf(DataSourceException.class);
        }
        pauses.clear();
        calls.set(0);

        assertThatThrownBy(() -> fetch(backoff, failing())).isInstanceOf(DataSourceException.class);
        assertThat(calls).as("열린 동안은 한 번만 묻는다").hasValue(1);
        assertThat(pauses).as("소스가 내려간 동안 콜마다 12초를 기다리지 않는다").isEmpty();

        assertThat(fetch(backoff, () -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("no")).getStatusCode().value())
                .isEqualTo(404);
        calls.set(0);
        assertThatThrownBy(() -> fetch(backoff, failing())).isInstanceOf(DataSourceException.class);
        assertThat(calls).as("응답(4xx여도)이 닫으면 다시 세 번 묻는다").hasValue(1 + SecFilingClient.TRANSIENT_RETRIES);
        assertThat(pauses).containsExactly(2_000L, 10_000L);
    }

    @Test
    @DisplayName("열린 차단은 마지막 소진 뒤 10분이 지나면 닫힌다 — 싱글턴이 열린 채 다음 실행으로 넘어가지 않는다")
    void openCircuitExpires() throws Exception {
        long[] now = {0};
        SecFilingClient.Backoff backoff = new SecFilingClient.Backoff(SecFilingClient.Backoff.STANDARD_MILLIS, pauses::add,
                () -> now[0]);
        for (int i = 0; i < SecFilingClient.Backoff.OPEN_AFTER; i++) {
            assertThatThrownBy(() -> fetch(backoff, failing())).isInstanceOf(DataSourceException.class);
        }
        now[0] += SecFilingClient.Backoff.CLOSE_AFTER_NANOS + 1;
        pauses.clear();
        calls.set(0);

        assertThatThrownBy(() -> fetch(backoff, failing())).isInstanceOf(DataSourceException.class);
        assertThat(calls).hasValue(1 + SecFilingClient.TRANSIENT_RETRIES);
        assertThat(pauses).containsExactly(2_000L, 10_000L);
    }

    @Test
    @DisplayName("대기 중 인터럽트는 다시 묻지 않고 TRANSIENT로 끝난다 — 인터럽트 깃발을 되살린다")
    void interruptDuringWaitStops() {
        SecFilingClient.Backoff backoff = new SecFilingClient.Backoff(SecFilingClient.Backoff.STANDARD_MILLIS, millis -> {
            throw new InterruptedException("종료");
        });

        assertThatThrownBy(() -> fetch(backoff, failing()))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
        assertThat(calls).as("첫 시도 뒤 대기에서 멈춘다").hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @DisplayName("리미터 대기의 인터럽트는 HTTP를 치지 않는다 — 다시 묻지 않는다")
    void interruptInTheLimiterNeverCalls() {
        RateLimiter interrupting = limiter(true, () -> {
            throw new InterruptedException("종료");
        });

        assertThatThrownBy(() -> SecFilingClient.fetch(interrupting, recording(), counted(ok()), r -> null, "시험"))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
        assertThat(calls).hasValue(0);
        assertThat(pauses).isEmpty();
    }

    @Test
    @DisplayName("한도를 다 쓰면 RATE_LIMITED이고 HTTP를 치지 않는다")
    void quotaExhaustedNeverCalls() {
        RateLimiter spent = limiter(false, () -> { });

        assertThatThrownBy(() -> SecFilingClient.fetch(spent, recording(), counted(ok()), r -> null, "시험"))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.RATE_LIMITED));
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("운영 생성자는 호스트마다 다른 차단을 둔다 — 하나를 나누면 공시 목록의 성공이 Archives 차단을 닫는다")
    void productionConstructorKeepsOneBackoffPerHost() throws Exception {
        // 행동 시험(교차 시나리오)은 패키지 생성자로 차단 둘을 직접 넣어, 운영 생성자를 하나 공유로 되돌린 변이에 51개가 다
        // 초록이었다(v14 반증 · 사본 ms). 그래서 운영 배선 자체를 묻는다.
        com.stockscreener.pipeline.datasource.http.DataSourceProperties properties =
                new com.stockscreener.pipeline.datasource.http.DataSourceProperties();
        properties.getSecEdgar().setApiKey("Test Runner test@example.com");
        SecFilingClient client = new SecFilingClient(new com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory(),
                properties, limiter(true, () -> { }));
        java.lang.reflect.Field data = SecFilingClient.class.getDeclaredField("dataBackoff");
        java.lang.reflect.Field files = SecFilingClient.class.getDeclaredField("filesBackoff");
        data.setAccessible(true);
        files.setAccessible(true);
        assertThat(data.get(client)).isNotNull().isNotSameAs(files.get(client));
    }

    private SecFilingClient.Backoff recording() {
        return new SecFilingClient.Backoff(SecFilingClient.Backoff.STANDARD_MILLIS, pauses::add);
    }

    private ResponseEntity<String> fetch(SecFilingClient.Backoff backoff, Supplier<ResponseEntity<String>> call)
            throws DataSourceException {
        return SecFilingClient.fetch(limiter(true, () -> { }), backoff, counted(call), r -> null, "시험");
    }

    private Supplier<ResponseEntity<String>> counted(Supplier<ResponseEntity<String>> call) {
        return () -> {
            calls.incrementAndGet();
            return call.get();
        };
    }

    private static Supplier<ResponseEntity<String>> failing() {
        return () -> {
            throw new IllegalStateException("Premature EOF");
        };
    }

    private static Supplier<ResponseEntity<String>> ok() {
        return () -> ResponseEntity.ok("{}");
    }

    @FunctionalInterface
    private interface Acquire {
        void run() throws InterruptedException;
    }

    private static RateLimiter limiter(boolean quota, Acquire acquire) {
        return new RateLimiter() {
            @Override
            public void acquire() throws InterruptedException {
                acquire.run();
            }

            @Override
            public boolean hasDailyQuota() {
                return quota;
            }

            @Override
            public long usedToday() {
                return 0;
            }

            @Override
            public String sourceName() {
                return "sec-edgar";
            }
        };
    }
}
