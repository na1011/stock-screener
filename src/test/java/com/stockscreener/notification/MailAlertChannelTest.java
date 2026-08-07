package com.stockscreener.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수신자 결정과 임계 적용을 검증한다. SMTP 서버도 자격증명도 필요 없다.
 *
 * <p>여기서 틀릴 수 있는 것은 <b>누구에게 가는가</b>다. 운영자 알림이 사용자에게 가거나
 * 워치리스트 알림이 운영자에게 가면 둘 다 조용한 사고이고, 실제 메일이 나가기 전까지
 * 드러나지 않는다.
 */
class MailAlertChannelTest {

    private final List<SimpleMailMessage> sent = new ArrayList<>();

    @Test
    @DisplayName("운영자 알림은 운영자 주소로 간다")
    void operatorEventGoesToOperator() {
        MailAlertChannel channel = channel("bot@example.com", "ops@example.com", AlertSeverity.ERROR);

        channel.deliver(AlertEvent.toOperator(AlertSeverity.ERROR, "실패", "사유", "k"));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getTo()).containsExactly("ops@example.com");
        assertThat(sent.get(0).getFrom()).isEqualTo("bot@example.com");
        assertThat(sent.get(0).getSubject()).isEqualTo("[ERROR] 실패");
    }

    @Test
    @DisplayName("운영자 주소를 비우면 발신 주소로 대체한다")
    void operatorAddressFallsBackToFrom() {
        MailAlertChannel channel = channel("bot@example.com", "", AlertSeverity.ERROR);

        channel.deliver(AlertEvent.toOperator(AlertSeverity.ERROR, "실패", "사유", "k"));

        assertThat(sent.get(0).getTo()).containsExactly("bot@example.com");
    }

    @Test
    @DisplayName("사용자 알림은 그 사용자의 주소로 간다")
    void userEventGoesToThatUser() {
        MailAlertChannel channel = channel("bot@example.com", "ops@example.com", AlertSeverity.ERROR);

        channel.deliver(AlertEvent.toUser(7L, AlertSeverity.INFO, "조건 충족", "본문", "k"));

        assertThat(sent.get(0).getTo()).containsExactly("user7@example.com");
    }

    @Test
    @DisplayName("사용자 알림은 심각도 임계를 받지 않는다 — 걸면 INFO인 워치리스트 알림이 통째로 사라진다")
    void userEventIgnoresThreshold() {
        MailAlertChannel channel = channel("bot@example.com", "ops@example.com", AlertSeverity.CRITICAL);

        assertThat(channel.accepts(AlertEvent.toUser(7L, AlertSeverity.INFO, "t", "b", "k"))).isTrue();
        assertThat(channel.accepts(AlertEvent.toOperator(AlertSeverity.ERROR, "t", "b", "k"))).isFalse();
    }

    @Test
    @DisplayName("수신자를 못 찾으면 던진다 — 조용히 건너뛰면 알림이 흔적 없이 사라진다")
    void throwsWhenRecipientUnknown() {
        MailAlertChannel channel = channel("bot@example.com", "ops@example.com", AlertSeverity.ERROR);

        assertThatThrownBy(() -> channel.deliver(
                AlertEvent.toUser(999L, AlertSeverity.INFO, "t", "b", "k")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("발신 주소가 없거나 JavaMailSender가 없으면 비활성이다")
    void inactiveWithoutCredentials() {
        assertThat(channel("", "ops@example.com", AlertSeverity.ERROR).isConfigured()).isFalse();

        AlertProperties.Mail properties = new AlertProperties.Mail();
        properties.setFrom("bot@example.com");
        assertThat(new MailAlertChannel(properties, null, directory()).isConfigured()).isFalse();
    }

    private MailAlertChannel channel(String from, String operator, AlertSeverity minSeverity) {
        AlertProperties.Mail properties = new AlertProperties.Mail();
        properties.setFrom(from);
        properties.setOperatorAddress(operator);
        properties.setMinSeverity(minSeverity);
        return new MailAlertChannel(properties, recordingSender(), directory());
    }

    /** 7번 사용자만 아는 디렉토리. 나머지는 "없음"이다. */
    private static AlertRecipientDirectory directory() {
        return userId -> userId == 7L ? Optional.of("user7@example.com") : Optional.empty();
    }

    /**
     * 보낸 메시지를 모으는 {@link JavaMailSender}.
     *
     * <p>Mockito 대신 직접 구현하는 이유는 {@code send(SimpleMailMessage...)}가 가변인자라
     * 목 검증이 배열 매처로 지저분해지기 때문이다. 필요한 것은 "무엇이 실렸는가" 하나다.
     */
    private JavaMailSender recordingSender() {
        return new JavaMailSenderStub(sent);
    }

    private record JavaMailSenderStub(List<SimpleMailMessage> sent)
            implements org.springframework.mail.javamail.JavaMailSender {

        @Override
        public void send(SimpleMailMessage... messages) {
            sent.addAll(List.of(messages));
        }

        @Override
        public jakarta.mail.internet.MimeMessage createMimeMessage() {
            throw new UnsupportedOperationException("이 채널은 SimpleMailMessage만 쓴다");
        }

        @Override
        public jakarta.mail.internet.MimeMessage createMimeMessage(java.io.InputStream contentStream) {
            throw new UnsupportedOperationException("이 채널은 SimpleMailMessage만 쓴다");
        }

        @Override
        public void send(jakarta.mail.internet.MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException("이 채널은 SimpleMailMessage만 쓴다");
        }
    }
}
