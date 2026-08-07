package com.stockscreener.notification;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 이메일 채널. spec §8이 요구하는 두 채널 중 하나다.
 *
 * <p>메신저와 역할이 갈린다. 메신저는 <b>운영자</b>가 즉시 보는 곳이고, 메일은 그에 더해
 * <b>사용자</b>에게 닿는 유일한 경로다 — 지인마다 메신저 채널을 만들 수는 없다.
 * 그래서 심각도 임계가 운영자 알림에만 걸린다.
 */
public class MailAlertChannel implements AlertChannel {

    private final AlertProperties.Mail properties;
    private final JavaMailSender mailSender;
    private final AlertRecipientDirectory recipients;

    public MailAlertChannel(AlertProperties.Mail properties, JavaMailSender mailSender,
                            AlertRecipientDirectory recipients) {
        this.properties = properties;
        this.mailSender = mailSender;
        this.recipients = recipients;
    }

    @Override
    public String name() {
        return "mail";
    }

    @Override
    public boolean isConfigured() {
        return mailSender != null
                && properties.getFrom() != null && !properties.getFrom().isBlank();
    }

    /**
     * 사용자 대상 알림은 심각도와 무관하게 받는다.
     *
     * <p>IMPORTANT — 여기에 임계를 걸면 워치리스트 알림이 통째로 사라진다. 그쪽은 실패가
     * 아니라 정상적인 사건이므로 심각도가 {@code INFO}이고, 운영자 기본 임계는 {@code ERROR}다.
     */
    @Override
    public boolean accepts(AlertEvent event) {
        return !event.isForOperator() || event.severity().atLeast(properties.getMinSeverity());
    }

    @Override
    public void deliver(AlertEvent event) {
        String to = resolveRecipient(event);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(to);
        message.setSubject(AlertRendering.subject(event));
        message.setText(AlertRendering.body(event));
        mailSender.send(message);
    }

    /**
     * 수신자를 정한다. 찾지 못하면 <b>던진다.</b>
     *
     * <p>조용히 건너뛰지 않는 이유는, 그러면 워치리스트 알림이 아무 흔적 없이 사라지고
     * 사용자는 그것을 "조건에 안 맞았나 보다"로 읽기 때문이다. 던지면 Composite이 잡아
     * 로그로 남기고, 아무 채널도 받지 못한 사건은 로그 폴백으로 한 번 더 남는다.
     */
    private String resolveRecipient(AlertEvent event) {
        if (event.isForOperator()) {
            String operator = properties.resolvedOperatorAddress();
            if (operator == null || operator.isBlank()) {
                throw new IllegalStateException("운영자 수신 주소가 설정되지 않았다");
            }
            return operator;
        }
        long userId = event.recipientUserId();
        return recipients.emailOf(userId).orElseThrow(() -> new IllegalStateException(
                "알림 수신자를 찾을 수 없다: userId=" + userId));
    }
}
