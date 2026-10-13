package com.stockscreener.pipeline.datasource.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 분당 허용량을 균등 간격으로 흘려보내고, 일일 총량을 함께 센다.
 *
 * <p>버스트를 허용하지 않고 <b>균등 간격</b>으로 내보내는 이유는, 분당 한도를 순간적으로
 * 몰아 쓰면 벤더 쪽 순간 부하 제한(공공데이터포털은 30 TPS)에 따로 걸리기 때문이다.
 * 어차피 배치는 급할 이유가 없다.
 *
 * <p>일일 카운터는 인메모리이며 자정(시스템 기본 시간대)에 리셋된다. 애플리케이션을
 * 재시작하면 카운터가 사라지지만, 온프레미스 상시구동 전제(spec §6)에서 재시작은 드물고
 * 목적이 "정확한 회계"가 아니라 "소진 시 우아한 중단"이므로 이 정도로 충분하다.
 * DB 영속화는 실측으로 필요가 확인되면 그때 붙인다 — 지금 만들면 근거 없는 복잡성이다.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final String sourceName;
    private final long minIntervalMillis;
    private final long dailyQuota;
    /** 일일 총량을 경로마다 세는가 — 그러면 {@link #dailyQuota}는 경로 하나의 한도이고 경로 없는 호출은 거절한다. */
    private final boolean perEndpoint;
    private final Clock clock;

    private long lastAcquiredAtMillis;
    private LocalDate counterDate;
    private long usedToday;
    private final Map<String, Long> usedTodayByEndpoint = new HashMap<>();

    /**
     * @param permitsPerMinute 분당 허용 호출 수. 0 이하면 속도 제한 없음
     * @param dailyQuota       일일 총량. 0 이하면 무제한
     */
    public TokenBucketRateLimiter(String sourceName, int permitsPerMinute, long dailyQuota) {
        this(sourceName, permitsPerMinute, dailyQuota, Clock.systemDefaultZone());
    }

    TokenBucketRateLimiter(String sourceName, int permitsPerMinute, long dailyQuota, Clock clock) {
        this(sourceName, permitsPerMinute, dailyQuota, false, clock);
    }

    private TokenBucketRateLimiter(String sourceName, int permitsPerMinute, long dailyQuota, boolean perEndpoint,
                                   Clock clock) {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("sourceName이 비어 있다.");
        }
        this.sourceName = sourceName;
        this.minIntervalMillis = permitsPerMinute > 0 ? 60_000L / permitsPerMinute : 0L;
        this.dailyQuota = dailyQuota;
        this.perEndpoint = perEndpoint;
        this.clock = clock;
        this.counterDate = LocalDate.now(clock);
    }

    /**
     * 속도는 소스 전체로, <b>일일 총량은 경로마다</b> 센다(ADR-0176 · DART). {@code dailyQuota}는 경로 하나의 한도다.
     *
     * <p>MUST: 이 리미터에는 경로 없는 {@link #acquire()}·{@link #hasDailyQuota()}를 부르지 않는다 — 부르면 던진다. 경로 없는
     * 콜이 하나라도 남으면 그 콜은 어느 경로의 한도에도 안 세어지므로, 조용히 넘기지 않고 그 자리를 드러낸다.
     */
    public static TokenBucketRateLimiter perEndpoint(String sourceName, int permitsPerMinute, long dailyQuotaPerEndpoint) {
        return new TokenBucketRateLimiter(sourceName, permitsPerMinute, dailyQuotaPerEndpoint, true,
                Clock.systemDefaultZone());
    }

    static TokenBucketRateLimiter perEndpoint(String sourceName, int permitsPerMinute, long dailyQuotaPerEndpoint,
                                              Clock clock) {
        return new TokenBucketRateLimiter(sourceName, permitsPerMinute, dailyQuotaPerEndpoint, true, clock);
    }

    @Override
    public synchronized void acquire() throws InterruptedException {
        requireNoEndpoint("acquire");
        pace();
    }

    @Override
    public synchronized void acquire(String endpoint) throws InterruptedException {
        if (!perEndpoint) {
            acquire();
            return;
        }
        pace();
        usedTodayByEndpoint.merge(requireEndpoint(endpoint), 1L, Long::sum);
    }

    /** 균등 간격을 지키고 소스 전체의 오늘 호출 수를 하나 올린다. */
    private void pace() throws InterruptedException {
        rolloverIfNewDay();

        if (minIntervalMillis > 0) {
            long now = clock.millis();
            long earliest = lastAcquiredAtMillis + minIntervalMillis;
            if (now < earliest) {
                Thread.sleep(earliest - now);
            }
            lastAcquiredAtMillis = clock.millis();
        }
        usedToday++;
    }

    @Override
    public synchronized boolean hasDailyQuota() {
        requireNoEndpoint("hasDailyQuota");
        rolloverIfNewDay();
        return dailyQuota <= 0 || usedToday < dailyQuota;
    }

    @Override
    public synchronized boolean hasDailyQuota(String endpoint) {
        if (!perEndpoint) {
            return hasDailyQuota();
        }
        rolloverIfNewDay();
        return dailyQuota <= 0 || usedTodayByEndpoint.getOrDefault(requireEndpoint(endpoint), 0L) < dailyQuota;
    }

    @Override
    public synchronized long usedToday(String endpoint) {
        if (!perEndpoint) {
            return usedToday();
        }
        rolloverIfNewDay();
        return usedTodayByEndpoint.getOrDefault(requireEndpoint(endpoint), 0L);
    }

    private void requireNoEndpoint(String method) {
        if (perEndpoint) {
            throw new IllegalStateException(sourceName + " 리미터는 일일 총량을 경로마다 센다 — " + method
                    + "(endpoint)를 부른다. 경로 없는 콜은 어느 한도에도 안 세어진다.");
        }
    }

    private static String requireEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("경로가 비어 있다.");
        }
        return endpoint;
    }

    @Override
    public synchronized long usedToday() {
        rolloverIfNewDay();
        return usedToday;
    }

    @Override
    public String sourceName() {
        return sourceName;
    }

    /**
     * 날짜가 바뀌면 카운터를 리셋한다.
     *
     * <p>스케줄러로 자정에 리셋하지 않고 조회 시점에 판정하는 이유는, 별도 스레드가
     * 필요 없고 애플리케이션이 자정에 꺼져 있어도 올바르게 동작하기 때문이다.
     */
    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(counterDate)) {
            log.info("일일 호출 카운터를 리셋한다. source={} 어제 사용={} ", sourceName, usedToday);
            if (perEndpoint) {
                log.info("경로별 일일 호출 수(어제): source={} {}", sourceName, usedTodayByEndpoint);
            }
            counterDate = today;
            usedToday = 0;
            usedTodayByEndpoint.clear();
        }
    }
}
