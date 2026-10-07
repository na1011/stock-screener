package com.stockscreener.pipeline.lock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code write_lock}의 한 행을 읽은 것 — 그리고 그것을 읽은 순간의 DB 시각.
 *
 * <p>행이 없으면 {@link #absent(Instant)}다({@code version}이 {@code null}). 행이 있는데 {@code token}이 비면 반납된 상태다.
 * 둘 다 「비었음」이지만 CAS가 다르다 — 행이 없으면 INSERT가, 있으면 version 비교 UPDATE가 이긴다.
 *
 * @param dbNow 이 행을 읽은 SELECT의 {@code now()}. 세션 TTL은 DB 시계로 잰다 — JVM과 셸의 시계를 섞지 않는다
 */
record LockRow(Long version, HolderKind kind, UUID token, String purpose, String sessionId,
               Long holderPid, Long holderStart, List<String> members,
               Instant acquiredAt, Instant heartbeatAt, Instant lastActivityAt,
               Long waiterPid, Long waiterStart, String waiterPurpose, Instant waiterSince,
               Instant dbNow) {

    LockRow {
        members = members == null ? List.of() : List.copyOf(members);
    }

    static LockRow absent(Instant dbNow) {
        return new LockRow(null, null, null, null, null, null, null, List.of(),
                null, null, null, null, null, null, null, dbNow);
    }

    boolean held() {
        return token != null;
    }

    boolean heldBy(ProcessIdentity process) {
        return held() && kind != HolderKind.SESSION
                && holderPid != null && holderPid == process.pid()
                && holderStart != null && holderStart == process.startEpochSecond();
    }

    boolean heldBySession(String id) {
        return held() && kind == HolderKind.SESSION && id.equals(sessionId);
    }

    boolean hasWaiter() {
        return waiterPid != null && waiterStart != null;
    }

    boolean waiterIs(ProcessIdentity process) {
        return hasWaiter() && waiterPid == process.pid() && waiterStart == process.startEpochSecond();
    }

    /** 보유 종류. {@code SESSION}은 JVM이 아니라 {@code ops/write-session.sh}의 세션이 쥔다. */
    enum HolderKind {
        WEB, BOOTRUN, SESSION
    }
}
