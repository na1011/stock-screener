package com.stockscreener.pipeline.datasource.kr;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * DART 일일 총량을 <b>URL 경로마다</b> 센다(ADR-0176). 속도(분당 600)는 {@code dartRateLimiter}가 키 합으로 따로 센다.
 *
 * <p><b>왜 경로마다인가</b>: 같은 키로 하루 약 28,400콜(두 경로)에 실패 0인 운영 기록이 「키 합 2만」의 반례다 — 키 합을 우리가 먼저
 * 걸면 소스가 막지 않는 날의 콜을 우리가 끊는다. 경로 단위 한도의 존재는 재지 않았다(넘는 날 DART는 {@code 020}을 낸다 → RATE_LIMITED).
 *
 * <p><b>왜 리미터({@code RateLimiter})에 넣지 않는가</b>: SEC 판 고정 시험({@code ReaderVersionPinTest})의 해시가 판 범위 파일이 쓰는
 * {@code RateLimiter}의 구현 전부를 싣는다 — 리미터 계층을 바꾸면 SEC 해석과 무관한 이 변경이 보충 판을 올려 저장분을 다시 받게 한다.
 *
 * <p>카운터는 인메모리이고 자정(시스템 시간대)에 비운다 — JVM마다 따로 센다(앱과 bootRun은 각자). 목적은 정확한 회계가 아니라
 * 소진 때 우아한 중단이다({@code TokenBucketRateLimiter}와 같은 이유).
 */
@Component
public class DartDailyQuota {

    private static final Logger log = LoggerFactory.getLogger(DartDailyQuota.class);

    private final long perPath;
    private final Clock clock;
    private final Map<String, Long> used = new HashMap<>();
    private LocalDate counterDate;

    @Autowired
    public DartDailyQuota(DataSourceProperties properties) {
        this(properties.getDart().getDailyQuota(), Clock.systemDefaultZone());
    }

    /**
     * @param perPath 경로 하나의 일일 한도. 0 이하면 무제한
     */
    DartDailyQuota(long perPath, Clock clock) {
        this.perPath = perPath;
        this.clock = clock;
        this.counterDate = LocalDate.now(clock);
    }

    /** 한도 없는 계수기 — 시험과 탐침이 쓴다. */
    public static DartDailyQuota unlimited() {
        return new DartDailyQuota(0, Clock.systemDefaultZone());
    }

    /** 그 경로의 오늘 한도가 남았는가. */
    public synchronized boolean has(String path) {
        rollover();
        return perPath <= 0 || used.getOrDefault(requirePath(path), 0L) < perPath;
    }

    /** 그 경로로 한 콜을 센다 — 토큰을 얻은 뒤 부른다. */
    public synchronized void count(String path) {
        rollover();
        used.merge(requirePath(path), 1L, Long::sum);
    }

    /** 그 경로로 오늘 쓴 콜 수. */
    public synchronized long used(String path) {
        rollover();
        return used.getOrDefault(requirePath(path), 0L);
    }

    private void rollover() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(counterDate)) {
            if (!used.isEmpty()) {
                log.info("DART 경로별 일일 호출 수(어제): {}", used);
            }
            used.clear();
            counterDate = today;
        }
    }

    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("경로가 비어 있다.");
        }
        return path;
    }
}
