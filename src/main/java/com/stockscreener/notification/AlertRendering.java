package com.stockscreener.notification;

import java.util.Map;

/**
 * {@link AlertEvent}를 사람이 읽는 글로 만든다.
 *
 * <p>채널마다 따로 만들지 않는 이유는, 그렇게 하면 메신저와 메일이 <b>같은 사건을 다르게
 * 설명하기 시작하기</b> 때문이다. 두 채널을 함께 받는 운영자에게는 그것이 서로 다른 사건으로
 * 보인다. 형식(제목/본문의 분리)만 채널이 정하고 문구는 여기 한 곳에서 나온다.
 *
 * <p>{@link LoggingAlertPort}는 이것을 쓰지 않는다 — 로그는 한 줄이어야 하고, 그 형식은
 * 이미 테스트가 고정하고 있다.
 */
public final class AlertRendering {

    private AlertRendering() {
    }

    /** 제목 한 줄. 메일 제목과 메신저 첫 줄이 같은 문구를 쓴다. */
    public static String subject(AlertEvent event) {
        return "[%s] %s".formatted(event.severity(), event.title());
    }

    /**
     * 본문. 사유와 부가 정보가 들어간다.
     *
     * <p>{@code context}를 줄마다 나눠 적는 이유는 {@code executionId} 같은 값이 곧
     * 관리자 화면에서 그 실행을 찾는 열쇠이기 때문이다. 맵을 통째로 찍으면 한 줄이 길어져
     * 메신저에서 잘린다.
     */
    public static String body(AlertEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.body() != null && !event.body().isBlank()) {
            sb.append(event.body());
        }
        Map<String, String> context = event.context();
        if (!context.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            context.forEach((key, value) -> sb.append(key).append(": ").append(value).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    /** 제목과 본문을 한 덩어리로. 메신저처럼 제목 칸이 없는 채널이 쓴다. */
    public static String plainText(AlertEvent event) {
        String body = body(event);
        return body.isEmpty() ? subject(event) : subject(event) + "\n" + body;
    }
}
