package com.stockscreener.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AlertSuppressorTest {

    private static final Instant T0 = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    @DisplayName("같은 키가 창 안에서 다시 오면 억제한다")
    void suppressesRepeatWithinWindow() {
        MutableClock clock = new MutableClock(T0);
        AlertSuppressor suppressor = new AlertSuppressor(Duration.ofMinutes(10), clock);

        assertThat(suppressor.suppress(event("k", AlertSeverity.ERROR))).isFalse();

        clock.advance(Duration.ofMinutes(9));
        assertThat(suppressor.suppress(event("k", AlertSeverity.ERROR))).isTrue();
    }

    @Test
    @DisplayName("창을 지나면 다시 보낸다 — 일일 배치는 하루 간격이라 여기 걸리지 않는다")
    void allowsAfterWindow() {
        MutableClock clock = new MutableClock(T0);
        AlertSuppressor suppressor = new AlertSuppressor(Duration.ofMinutes(10), clock);

        suppressor.suppress(event("k", AlertSeverity.ERROR));
        clock.advance(Duration.ofMinutes(11));

        assertThat(suppressor.suppress(event("k", AlertSeverity.ERROR))).isFalse();
    }

    @Test
    @DisplayName("심각도가 오르면 억제하지 않는다 — 부분 실패 뒤의 완전 실패를 삼키면 안 된다")
    void doesNotSuppressEscalation() {
        MutableClock clock = new MutableClock(T0);
        AlertSuppressor suppressor = new AlertSuppressor(Duration.ofMinutes(10), clock);

        assertThat(suppressor.suppress(event("daily:2026-08-05", AlertSeverity.WARNING))).isFalse();
        assertThat(suppressor.suppress(event("daily:2026-08-05", AlertSeverity.CRITICAL))).isFalse();
    }

    @Test
    @DisplayName("다른 키는 서로 막지 않는다")
    void differentKeysAreIndependent() {
        AlertSuppressor suppressor = new AlertSuppressor(Duration.ofMinutes(10), new MutableClock(T0));

        assertThat(suppressor.suppress(event("a", AlertSeverity.ERROR))).isFalse();
        assertThat(suppressor.suppress(event("b", AlertSeverity.ERROR))).isFalse();
    }

    @Test
    @DisplayName("키가 없으면 억제하지 않는다 — 억제를 원하는 쪽이 키를 실어 보낸다")
    void neverSuppressesWithoutKey() {
        AlertSuppressor suppressor = new AlertSuppressor(Duration.ofMinutes(10), new MutableClock(T0));

        assertThat(suppressor.suppress(event(null, AlertSeverity.ERROR))).isFalse();
        assertThat(suppressor.suppress(event(null, AlertSeverity.ERROR))).isFalse();
        assertThat(suppressor.suppress(event("  ", AlertSeverity.ERROR))).isFalse();
    }

    @Test
    @DisplayName("창이 0이면 억제가 꺼진다")
    void zeroWindowDisablesSuppression() {
        AlertSuppressor suppressor = new AlertSuppressor(Duration.ZERO, new MutableClock(T0));

        assertThat(suppressor.suppress(event("k", AlertSeverity.ERROR))).isFalse();
        assertThat(suppressor.suppress(event("k", AlertSeverity.ERROR))).isFalse();
    }

    private static AlertEvent event(String dedupeKey, AlertSeverity severity) {
        return AlertEvent.toOperator(severity, "제목", "본문", dedupeKey);
    }

    /** 테스트가 시간을 미는 시계. {@code Clock.fixed}로는 창 경과를 표현할 수 없다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
