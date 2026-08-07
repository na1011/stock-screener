package com.stockscreener.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

/**
 * 알림 채널을 조립한다.
 *
 * <p>IMPORTANT — <b>채널의 활성 여부를 빈 등록 조건으로 표현하지 않는다.</b> 채널을
 * {@code @Conditional}로 걸면 "채널이 하나도 없는 상태"를 판정하는 자리가 사라지고,
 * 그때 {@link AlertPort}를 무엇으로 채울지가 여러 조건의 <b>우연한 조합</b>이 된다.
 * 실제 위험은 하나다 — 채널 구현을 등록하는 순간 기본 구현이 물러나 <b>알림이 통째로
 * 증발하는데 아무 테스트도 깨지지 않는</b> 구성이 만들어지는 것이다.
 *
 * <p>그래서 채널은 항상 등록하고, {@link AlertChannel#isConfigured()}로 거른 뒤
 * <b>같은 메서드 안에서</b> 폴백까지 정한다. 알림이 갈 곳 없이 남는 경로가 구조적으로
 * 존재하지 않게 된다.
 *
 * <p>{@code @ConditionalOnMissingBean}은 그대로 둔다 — 테스트가 자기 {@link AlertPort}를
 * 심어 통지를 가로채는 경로가 이미 있다({@code PipelineEndToEndTest}).
 */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertConfig.class);

    @Bean
    public AlertSuppressor alertSuppressor(AlertProperties properties) {
        return new AlertSuppressor(properties.getSuppressionWindow());
    }

    @Bean
    public WebhookAlertChannel webhookAlertChannel(AlertProperties properties) {
        return new WebhookAlertChannel(properties.getWebhook());
    }

    /**
     * {@link JavaMailSender}를 {@code ObjectProvider}로 받는 이유는 {@code spring.mail.host}가
     * 없으면 Boot가 그 빈을 만들지 않기 때문이다. 직접 주입하면 메일을 안 쓰는 환경에서
     * <b>기동이 실패한다</b> — 알림 하나 때문에 애플리케이션 전체가 뜨지 않는 것은 과하다.
     */
    @Bean
    public MailAlertChannel mailAlertChannel(AlertProperties properties,
                                             ObjectProvider<JavaMailSender> mailSender,
                                             AlertRecipientDirectory recipients) {
        return new MailAlertChannel(properties.getMail(), mailSender.getIfAvailable(), recipients);
    }

    @Bean
    @ConditionalOnMissingBean(AlertPort.class)
    public AlertPort alertPort(List<AlertChannel> channels, AlertSuppressor suppressor) {
        // 폴백은 채널 구성 여부와 무관하게 항상 만든다. 활성 채널이 없으면 이것이 곧
        // AlertPort이고, 있으면 "아무 채널도 받지 못한 사건"의 마지막 자리가 된다.
        LoggingAlertPort logging = new LoggingAlertPort();

        List<AlertChannel> active = channels.stream().filter(AlertChannel::isConfigured).toList();
        if (active.isEmpty()) {
            log.info("알림 채널이 하나도 구성되지 않아 로그 기반 기본 구현을 쓴다. "
                    + "ALERT_WEBHOOK_URL·MAIL_USERNAME을 채우면 실제 통지가 나간다.");
            return logging;
        }

        // MUST NOT: 여기에 웹훅 URL이나 메일 주소를 찍지 않는다. 이름만으로 "실렸는가"는 판정된다.
        log.info("알림 채널 {}개를 구성했다: {}", active.size(),
                active.stream().map(AlertChannel::name).toList());
        return new CompositeAlertPort(active, suppressor, logging);
    }
}
