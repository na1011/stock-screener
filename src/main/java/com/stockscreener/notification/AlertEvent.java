package com.stockscreener.notification;

import java.util.Map;

/**
 * 통지할 사건 하나.
 *
 * @param dedupeKey     같은 키의 알림이 짧은 간격으로 반복되면 억제할 수 있게 하는 식별자.
 *                      IMPORTANT — 배치가 850종목 실패했을 때 850통을 보내면 알림 자체가
 *                      무의미해진다. 배치는 종목마다 보내지 않고 요약 한 건을 보내지만,
 *                      워치리스트처럼 사건마다 발생하는 알림에는 이 키가 필요하다.
 *                      억제 정책의 구현은 Phase 6의 몫이고, 여기서는 판단 근거만 실어 보낸다.
 * @param recipientUserId null이면 운영자(본인)에게 간다. 워치리스트 알림은 사용자를 지정한다.
 *                        이 필드가 있어서 배치와 워치리스트가 같은 포트를 쓸 수 있다.
 * @param context       화면 링크·실행 id 같은 부가 정보. 채널마다 표현이 다르므로
 *                      문자열 맵으로 두고 렌더링은 구현체에 맡긴다.
 */
public record AlertEvent(
        AlertSeverity severity,
        String title,
        String body,
        String dedupeKey,
        Long recipientUserId,
        Map<String, String> context
) {

    public AlertEvent {
        if (severity == null) {
            throw new IllegalArgumentException("severity가 null이다.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title이 비어 있다 — 제목 없는 알림은 읽히지 않는다.");
        }
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    /** 운영자에게 보내는 알림. 배치 실패가 이 경로를 쓴다. */
    public static AlertEvent toOperator(
            AlertSeverity severity, String title, String body, String dedupeKey) {
        return new AlertEvent(severity, title, body, dedupeKey, null, Map.of());
    }

    public static AlertEvent toOperator(
            AlertSeverity severity, String title, String body, String dedupeKey,
            Map<String, String> context) {
        return new AlertEvent(severity, title, body, dedupeKey, null, context);
    }

    /** 특정 사용자에게 보내는 알림. Phase 6의 워치리스트가 이 경로를 쓴다. */
    public static AlertEvent toUser(
            long userId, AlertSeverity severity, String title, String body, String dedupeKey) {
        return new AlertEvent(severity, title, body, dedupeKey, userId, Map.of());
    }

    public boolean isForOperator() {
        return recipientUserId == null;
    }
}
