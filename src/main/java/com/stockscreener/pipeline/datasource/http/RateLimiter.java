package com.stockscreener.pipeline.datasource.http;

/**
 * 외부 소스별 호출 속도·총량 제한.
 *
 * <p>배치 스레드에서 순차 호출하므로 블로킹이 옳다. 논블로킹으로 만들면 호출부가
 * 비동기 조합을 다뤄야 하는데, 얻는 것이 없다 — 어차피 다음 종목을 처리하려면 기다려야 한다.
 *
 * <p>새 의존성(Resilience4j 등)을 쓰지 않는 이유는 필요한 것이 서킷브레이커·벌크헤드가
 * 아니라 "분당 N회를 넘지 않는다" 하나이기 때문이다.
 */
public interface RateLimiter {

    /**
     * 토큰 하나를 얻을 때까지 블록한다.
     *
     * @throws InterruptedException 대기 중 인터럽트. 호출부는 이것을 삼키지 말고
     *                              스레드의 인터럽트 상태를 복원해야 한다.
     */
    void acquire() throws InterruptedException;

    /**
     * 일일 총량이 남아 있는가.
     *
     * <p>소진은 <b>실패가 아니라 정상적인 일시정지</b>다. 배치는 이것이 false가 되면
     * 우아하게 중단하고({@code ABORTED}), 다음 실행이 이어서 처리한다.
     */
    boolean hasDailyQuota();

    /** 오늘 사용한 호출 수. 관리자 화면과 로그가 쓴다. */
    long usedToday();

    /** 이 리미터가 담당하는 소스 이름. */
    String sourceName();

    /**
     * 경로 하나의 콜로 토큰을 얻는다. 속도(분당)는 경로와 무관하게 소스 전체로 센다.
     *
     * <p>일일 총량을 경로마다 세는 리미터({@link TokenBucketRateLimiter#perEndpoint})만 경로를 쓴다 — 나머지는 경로를 무시하고
     * {@link #acquire()}와 같다. 경로는 요청 URL의 경로 그대로다(예: {@code /fnlttSinglAcnt.json}).
     */
    default void acquire(String endpoint) throws InterruptedException {
        acquire();
    }

    /** 그 경로의 일일 총량이 남아 있는가. 경로마다 세지 않는 리미터는 {@link #hasDailyQuota()}와 같다. */
    default boolean hasDailyQuota(String endpoint) {
        return hasDailyQuota();
    }

    /** 그 경로로 오늘 쓴 호출 수. 경로마다 세지 않는 리미터는 {@link #usedToday()}와 같다. */
    default long usedToday(String endpoint) {
        return usedToday();
    }
}
