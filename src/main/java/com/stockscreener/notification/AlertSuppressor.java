package com.stockscreener.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 같은 사건이 짧은 간격으로 반복 통지되는 것을 막는다.
 *
 * <p>{@link AlertEvent#dedupeKey()}가 이 판정의 근거다. 그 필드는 Phase 2에 이미 실려 있었고
 * 억제 구현만 비어 있었다.
 *
 * <p>메모리에만 둔다. 애플리케이션을 재시작하면 억제가 풀리는데, 그것이 <b>맞는 동작</b>이다 —
 * 재시작은 대개 배포나 장애 대응이고, 그 직후의 알림은 오히려 보고 싶은 것이다. 억제 상태를
 * DB에 두면 알림 경로가 DB에 의존하게 되어, DB 장애를 알리려는 알림이 DB 때문에 막힌다.
 *
 * <p>심각도를 키에 섞는 이유는 <b>에스컬레이션을 삼키지 않기 위해서</b>다. 같은 잡이 부분
 * 실패({@code WARNING})했다가 곧 완전 실패({@code CRITICAL})하면 뒤의 것이 더 중요한데,
 * dedupeKey만으로 묶으면 그것이 조용히 사라진다.
 */
public class AlertSuppressor {

    /**
     * 항목 수 상한. 넘으면 통째로 비운다.
     *
     * <p>키가 무한히 늘어날 여지가 실제로 있다 — 워치리스트 알림의 키에는 사용자와 날짜가
     * 들어간다. 만료된 항목만 골라 지우는 청소 스레드를 두는 대신 상한에서 비우는 이유는,
     * 비워서 생기는 최악이 "알림이 한 번 더 간다"이기 때문이다. 그 반대(알림이 안 간다)만
     * 피하면 된다.
     */
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Instant> lastSentAt = new ConcurrentHashMap<>();
    private final Duration window;
    private final Clock clock;

    public AlertSuppressor(Duration window) {
        this(window, Clock.systemUTC());
    }

    /** 테스트가 시간을 밀 수 있게 시계를 주입받는다. */
    public AlertSuppressor(Duration window, Clock clock) {
        this.window = window == null ? Duration.ZERO : window;
        this.clock = clock;
    }

    /**
     * 이 사건을 억제해야 하는가. 억제하지 않기로 하면 <b>보낸 것으로 기록한다.</b>
     *
     * <p>판정과 기록을 한 번에 하는 이유는 둘을 나누면 호출부가 기록을 빠뜨릴 수 있고,
     * 그러면 억제가 영원히 동작하지 않는데 아무 증상도 없기 때문이다.
     *
     * @return 억제 대상이면 {@code true}
     */
    public boolean suppress(AlertEvent event) {
        String dedupeKey = event.dedupeKey();
        // 키가 없으면 억제하지 않는다. 억제를 원하는 쪽이 키를 실어 보낸다는 것이 이 필드의 계약이다.
        if (dedupeKey == null || dedupeKey.isBlank() || window.isZero() || window.isNegative()) {
            return false;
        }

        String key = event.severity().name() + '|' + dedupeKey;
        Instant now = clock.instant();

        if (lastSentAt.size() >= MAX_ENTRIES) {
            lastSentAt.clear();
        }

        // compute로 판정과 기록을 한 번에 한다. get 후 put으로 나누면 두 스레드가 같은 키를
        // 동시에 통과시킬 수 있고, 그러면 억제가 있으나 마나가 된다.
        AtomicBoolean suppressed = new AtomicBoolean();
        lastSentAt.compute(key, (ignored, previous) -> {
            if (previous != null && Duration.between(previous, now).compareTo(window) < 0) {
                suppressed.set(true);
                return previous;
            }
            return now;
        });
        return suppressed.get();
    }
}
