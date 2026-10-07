package com.stockscreener.pipeline.lock;

/**
 * 쓰기 잠금을 다른 보유자(또는 그 차례를 기다리는 쓰기 크론)가 쥐고 있어 거절했다(ADR-0175).
 *
 * <p>관리자 launch·원장 판정 {@code PUT}은 이것을 409로 옮기고 보유자를 싣는다 — 화면이 「무엇이 언제까지」를 보여 줘야
 * 사람이 기다릴지 끝낼지를 고른다. bootRun·웹 기동은 이 문구로 종료코드 1이 된다.
 */
public class WriteLockHeldException extends RuntimeException {

    private final transient WriteLock.Holder holder;

    public WriteLockHeldException(String message, WriteLock.Holder holder) {
        super(message);
        this.holder = holder;
    }

    /** 거절의 상대. 잠금 표에 닿지 못해 거절했으면 {@code null}이다. */
    public WriteLock.Holder holder() {
        return holder;
    }
}
