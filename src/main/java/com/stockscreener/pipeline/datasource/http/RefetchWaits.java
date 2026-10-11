package com.stockscreener.pipeline.datasource.http;

/**
 * 같은 호출을 다시 묻기 전의 대기 — 첫 실패 뒤 2초 · 둘째 실패 뒤 10초.
 *
 * <p><b>왜 기다리는가</b>: 대기 없이 다시 물으면 시도가 약 0.4초 안에 끝나 그보다 긴 끊김(「Connect timed out」·「Network is
 * down」)을 못 넘는다 — SEC 공시 받기의 {@code SecFilingClient.Backoff}가 같은 근거(2026-10-06 v11 반증)로 같은 값을 쓴다. 그
 * 클래스는 공시 보충 판 범위 안이고 호스트별 차단까지 지니므로 여기 따로 둔다 — 매핑 캐시(SEC 티커 · DART 고유번호)가 쓴다.
 * 매핑 캐시는 다시 묻기를 다 쓴 실패를 10분 기억하므로, 대기가 없으면 순간 끊김 하나가 10분 동안 그 시장 전 종목의 실패가
 * 된다(구현 반증 · ADR-0174).
 *
 * <p>시험은 대기를 주입한다 — 실제로 12초를 자지 않는다.
 */
public final class RefetchWaits {

    /** 시도 사이 대기 — 첫 실패 뒤 2초 · 둘째 실패 뒤 10초. */
    static final long[] STANDARD_MILLIS = {2_000, 10_000};

    @FunctionalInterface
    public interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    private final long[] millis;
    private final Pause pause;

    public RefetchWaits(long[] millis, Pause pause) {
        this.millis = millis.clone();
        this.pause = pause;
    }

    public static RefetchWaits standard() {
        return new RefetchWaits(STANDARD_MILLIS, Thread::sleep);
    }

    /** 다시 묻는 횟수(처음 한 번을 빼고). */
    public int retries() {
        return millis.length;
    }

    /** {@code retry}번째(0부터) 다시 묻기 전에 기다린다. */
    public void before(int retry) throws InterruptedException {
        pause.sleep(millis[retry]);
    }
}
