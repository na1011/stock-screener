package com.stockscreener.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * 알림 채널이 구성되기 전(Phase 6 이전)의 기본 구현.
 *
 * <p>MUST NOT: 조용히 버리지 않는다. 로그로 남겨야 개발 중 배치 실패를 인지할 수 있고,
 * Phase 6에서 Sentry가 붙으면 ERROR 이상의 로그가 자동으로 수집된다. 아무 일도 하지 않는
 * no-op으로 두면 "알림이 갔다고 생각했는데 아무 데도 가지 않은" 상태가 되고,
 * 그것은 배치 실패보다 늦게 발견된다.
 *
 * <p>심각도를 로그 레벨에 대응시키는 이유는 Phase 6의 Sentry가 레벨로 수집 대상을
 * 거르기 때문이다. 전부 info로 남기면 그때 다시 손봐야 한다.
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

    /**
     * Phase 6이 실제 채널 구현체를 등록하면 이 기본 구현은 물러난다.
     *
     * <p>{@code @ConditionalOnMissingBean}을 클래스가 아니라 {@code @Bean} 메서드에 거는 이유는,
     * 컴포넌트 스캔으로 등록되는 빈에는 이 조건이 걸리지 않기 때문이다. 스캔 시점에는
     * 다른 빈의 존재 여부가 아직 결정되지 않는다.
     */
    @Configuration
    public static class DefaultAlertPortConfig {

        @Bean
        @ConditionalOnMissingBean(AlertPort.class)
        public AlertPort loggingAlertPort() {
            log.info("알림 채널이 구성되지 않아 로그 기반 기본 구현을 쓴다. "
                    + "실제 이메일·메신저 통지는 Phase 6에서 붙는다.");
            return new LoggingAlertPort();
        }
    }
}
