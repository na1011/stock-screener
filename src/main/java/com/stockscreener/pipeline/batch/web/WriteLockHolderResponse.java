package com.stockscreener.pipeline.batch.web;

import com.stockscreener.pipeline.lock.WriteLock;

import java.time.Instant;

/**
 * 관리자 잡을 지금 띄우면 거절할 쓰기 잠금 보유자(ADR-0175 · 잠금 결정 12). 409 문제 응답({@code write-lock-held})의 속성과 같은
 * 칸이다 — 화면이 버튼을 잠근 사유와 눌렀을 때의 거절 사유가 같은 말을 하게.
 *
 * @param kind      {@code WEB}(이 앱) · {@code BOOTRUN} · {@code SESSION}(손 세션) · {@code WAITER}(차례를 기다리는 쓰기 크론)
 * @param pid       보유 JVM의 pid. 세션이면 {@code null}
 * @param sessionId 손 세션 id. 세션이 아니면 {@code null}
 * @param purpose   무엇을 위해 쥐었나({@code cron:…} · {@code admin:…} · {@code job:…})
 * @param since     언제부터. 이 JVM이 지금 쥔 것이면 {@code null}
 * @param waiting   보유가 아니라 대기 중인 쓰기 크론 때문인가
 */
public record WriteLockHolderResponse(String kind, Long pid, String sessionId, String purpose, Instant since,
                                      boolean waiting) {

    public static WriteLockHolderResponse of(WriteLock.Holder holder) {
        return new WriteLockHolderResponse(holder.kind(), holder.pid(), holder.sessionId(), holder.purpose(),
                holder.since(), holder.waiting());
    }
}
