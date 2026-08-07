package com.stockscreener.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 알림 채널이 구성되지 않은 환경의 기본 구현이자, 구성된 뒤에도 남는 <b>마지막 방어선</b>.
 *
 * <p>MUST NOT: 조용히 버리지 않는다. 로그로 남겨야 개발 중 배치 실패를 인지할 수 있고,
 * Sentry가 붙으면 ERROR 이상의 로그가 자동으로 수집된다. 아무 일도 하지 않는
 * no-op으로 두면 "알림이 갔다고 생각했는데 아무 데도 가지 않은" 상태가 되고,
 * 그것은 배치 실패보다 늦게 발견된다.
 *
 * <p>심각도를 로그 레벨에 대응시키는 이유는 Sentry가 레벨로 수집 대상을 거르기 때문이다.
 * 전부 info로 남기면 그때 다시 손봐야 한다.
 *
 * <p>채널이 붙어도 <b>이 클래스는 물러나지 않는다.</b> {@link CompositeAlertPort}가
 * "어느 채널도 받지 못한 사건"을 여기로 보낸다 — 등록은 {@link AlertConfig}가 한다.
 */
public class LoggingAlertPort implements AlertPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertPort.class);

    @Override
    public void send(AlertEvent event) {
        String rendered = render(event);
        switch (event.severity()) {
            case INFO -> log.info(rendered);
            case WARNING -> log.warn(rendered);
            case ERROR, CRITICAL -> log.error(rendered);
        }
    }

    private static String render(AlertEvent event) {
        StringBuilder sb = new StringBuilder("[ALERT/").append(event.severity()).append("] ")
                .append(event.title());
        if (event.body() != null && !event.body().isBlank()) {
            sb.append(" — ").append(event.body());
        }
        if (event.dedupeKey() != null && !event.dedupeKey().isBlank()) {
            sb.append(" (key=").append(event.dedupeKey()).append(')');
        }
        if (!event.isForOperator()) {
            sb.append(" (userId=").append(event.recipientUserId()).append(')');
        }
        if (!event.context().isEmpty()) {
            sb.append(' ').append(event.context());
        }
        return sb.toString();
    }
}
