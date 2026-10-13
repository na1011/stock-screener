package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.TokenBucketRateLimiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DART 일일 총량을 경로마다 센다(ADR-0176).
 */
class DartDailyQuotaTest {

    @Test
    @DisplayName("두 경로가 각자 한도까지 간다 — 대조군: 같은 경로는 한도에서 막힌다")
    void countsEachPathSeparately() throws Exception {
        DartDailyQuota quota = new DartDailyQuota(2, Clock.systemDefaultZone());
        TokenBucketRateLimiter pace = new TokenBucketRateLimiter("dart", 0, 0);

        DartResponse.acquire(pace, quota, "/fnlttSinglAcnt.json", "t");
        DartResponse.acquire(pace, quota, "/fnlttSinglAcnt.json", "t");
        assertThatThrownBy(() -> DartResponse.acquire(pace, quota, "/fnlttSinglAcnt.json", "t"))
                .isInstanceOf(DataSourceException.class)
                .extracting(e -> ((DataSourceException) e).kind()).isEqualTo(DataSourceException.Kind.RATE_LIMITED);

        DartResponse.acquire(pace, quota, "/fnlttSinglAcntAll.json", "t");
        assertThat(quota.used("/fnlttSinglAcntAll.json")).as("다른 경로는 한도와 무관하게 간다").isEqualTo(1);
        assertThat(quota.used("/fnlttSinglAcnt.json")).as("막힌 콜은 안 센다").isEqualTo(2);
        assertThat(pace.usedToday()).as("속도는 키 합으로 센다").isEqualTo(3);
    }

    @Test
    @DisplayName("날짜가 바뀌면 경로별 카운터를 비운다 · 0 이하면 무제한")
    void rollsOverAndTreatsNonPositiveAsUnlimited() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T14:59:00Z"));
        DartDailyQuota quota = new DartDailyQuota(1, clock);
        quota.count("/list.json");
        assertThat(quota.has("/list.json")).isFalse();
        clock.advance(Duration.ofHours(2));
        assertThat(quota.has("/list.json")).isTrue();
        assertThat(quota.used("/list.json")).isZero();

        DartDailyQuota unlimited = DartDailyQuota.unlimited();
        for (int i = 0; i < 100; i++) {
            unlimited.count("/list.json");
        }
        assertThat(unlimited.has("/list.json")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
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
