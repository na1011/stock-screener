package com.stockscreener.pipeline.datasource.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 레이트리밋과 일일 쿼터를 검증한다.
 *
 * <p>실제로 기다리면 테스트가 느려지므로 {@link Clock}을 주입해 시간을 조작한다.
 * 다만 간격 제어 자체는 {@code Thread.sleep}을 쓰므로, 여기서는 <b>카운터와 쿼터 판정</b>을
 * 검증하고 실제 대기는 분당 허용량을 크게 잡아 회피한다.
 */
class TokenBucketRateLimiterTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("일일 쿼터를 소진하면 hasDailyQuota가 false가 된다 — 실패가 아니라 일시정지 신호다")
    void exhaustsDailyQuota() throws Exception {
        RateLimiter limiter = new TokenBucketRateLimiter("test", 0, 3);

        assertThat(limiter.hasDailyQuota()).isTrue();
        limiter.acquire();
        limiter.acquire();
        assertThat(limiter.hasDailyQuota()).isTrue();
        limiter.acquire();

        assertThat(limiter.hasDailyQuota()).isFalse();
        assertThat(limiter.usedToday()).isEqualTo(3);
    }

    @Test
    @DisplayName("쿼터가 0 이하면 무제한이다")
    void treatsNonPositiveQuotaAsUnlimited() throws Exception {
        RateLimiter limiter = new TokenBucketRateLimiter("test", 0, 0);

        for (int i = 0; i < 100; i++) {
            limiter.acquire();
        }
        assertThat(limiter.hasDailyQuota()).isTrue();
    }

    @Test
    @DisplayName("경로마다 세는 리미터는 두 경로가 각자 한도까지 간다 — 키 합으로 세면 한 경로가 다른 경로를 막는다(ADR-0176)")
    void perEndpointQuotaCountsEachPathSeparately() throws Exception {
        RateLimiter limiter = TokenBucketRateLimiter.perEndpoint("dart", 0, 2);

        limiter.acquire("/fnlttSinglAcnt.json");
        limiter.acquire("/fnlttSinglAcnt.json");
        assertThat(limiter.hasDailyQuota("/fnlttSinglAcnt.json")).isFalse();
        assertThat(limiter.hasDailyQuota("/fnlttSinglAcntAll.json")).as("다른 경로는 안 썼다").isTrue();
        limiter.acquire("/fnlttSinglAcntAll.json");
        limiter.acquire("/fnlttSinglAcntAll.json");
        assertThat(limiter.hasDailyQuota("/fnlttSinglAcntAll.json")).isFalse();
        assertThat(limiter.usedToday("/fnlttSinglAcnt.json")).isEqualTo(2);
        assertThat(limiter.usedToday()).as("소스 전체 합계는 그대로 센다").isEqualTo(4);
    }

    @Test
    @DisplayName("대조군 — 경로마다 세지 않는 리미터는 경로가 달라도 합으로 막는다")
    void plainLimiterIgnoresTheEndpoint() throws Exception {
        RateLimiter limiter = new TokenBucketRateLimiter("dart", 0, 2);

        limiter.acquire("/fnlttSinglAcnt.json");
        limiter.acquire("/fnlttSinglAcntAll.json");

        assertThat(limiter.hasDailyQuota("/company.json")).isFalse();
    }

    @Test
    @DisplayName("경로마다 세는 리미터에 경로 없는 콜은 던진다 — 어느 한도에도 안 세어지는 콜을 조용히 넘기지 않는다")
    void perEndpointRejectsCallsWithoutAPath() {
        RateLimiter limiter = TokenBucketRateLimiter.perEndpoint("dart", 0, 2);

        assertThatThrownBy(limiter::acquire).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(limiter::hasDailyQuota).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("경로별 카운터도 날짜가 바뀌면 리셋된다")
    void perEndpointRollsOver() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T14:59:00Z"));
        RateLimiter limiter = TokenBucketRateLimiter.perEndpoint("dart", 0, 1, clock);

        limiter.acquire("/list.json");
        assertThat(limiter.hasDailyQuota("/list.json")).isFalse();
        clock.advance(Duration.ofHours(2));

        assertThat(limiter.hasDailyQuota("/list.json")).isTrue();
        assertThat(limiter.usedToday("/list.json")).isZero();
    }

    @Test
    @DisplayName("날짜가 바뀌면 카운터가 리셋된다 — 스케줄러 없이 조회 시점에 판정한다")
    void rollsOverAtMidnight() throws Exception {
        // KST 기준 2026-08-06 23:59 (UTC 14:59). 시간대를 헷갈리면 날짜 경계를 넘지 않아
        // 테스트가 코드 결함처럼 실패한다 — 실제로 한 번 그렇게 실패했다.
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T14:59:00Z"));
        RateLimiter limiter = new TokenBucketRateLimiter("test", 0, 2, clock);

        limiter.acquire();
        limiter.acquire();
        assertThat(limiter.hasDailyQuota()).isFalse();

        // 다음 날로 넘긴다. 애플리케이션이 자정에 꺼져 있어도 올바르게 동작해야 한다.
        clock.advance(Duration.ofHours(2));

        assertThat(limiter.hasDailyQuota()).isTrue();
        assertThat(limiter.usedToday()).isZero();
    }

    @Test
    @DisplayName("분당 허용량을 균등 간격으로 흘려보낸다")
    void spacesOutCalls() throws Exception {
        // 분당 600회 = 100ms 간격. 3회면 최소 200ms가 걸린다.
        RateLimiter limiter = new TokenBucketRateLimiter("test", 600, 0);

        long start = System.nanoTime();
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis)
                .as("버스트를 허용하면 벤더의 순간 부하 제한(30 TPS)에 따로 걸린다")
                .isGreaterThanOrEqualTo(150);
    }

    @Test
    @DisplayName("소스 이름을 요구한다 — 어느 소스의 한도인지 모르면 로그가 쓸모없다")
    void requiresSourceName() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new TokenBucketRateLimiter(" ", 60, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 시간을 앞으로 돌릴 수 있는 Clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
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
