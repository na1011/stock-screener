package com.stockscreener.notification;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조립 결과를 컨텍스트에서 확인한다.
 *
 * <p>이 테스트가 막는 결함은 하나다 — <b>채널을 등록하는 순간 로그 기반 기본 구현이 물러나
 * 알림이 통째로 증발하는 것.</b> 단위 테스트로는 잡히지 않는다. 각 클래스는 저마다 올바르게
 * 동작하고, 틀린 것은 <b>어느 빈이 {@code AlertPort}가 되는가</b>뿐이기 때문이다.
 * 자격증명이 없는 CI와 개발 환경이 정확히 이 상태다.
 */
class AlertConfigTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @Import(TestDatabaseGuard.class)
    @DisplayName("자격증명이 없을 때")
    class WithoutCredentials {

        @Autowired
        private AlertPort alertPort;

        @Autowired
        private WebhookAlertChannel webhookChannel;

        @Autowired
        private MailAlertChannel mailChannel;

        @Test
        @DisplayName("로그 기반 기본 구현이 AlertPort가 된다 — 알림이 증발하지 않는다")
        void fallsBackToLogging() {
            assertThat(alertPort).isInstanceOf(LoggingAlertPort.class);
        }

        @Test
        @DisplayName("채널 빈 자체는 등록되어 있고 비활성일 뿐이다")
        void channelsRegisteredButInactive() {
            // 등록 조건이 아니라 isConfigured()로 활성 여부를 표현한 결과다.
            // 이래야 "활성 채널이 하나도 없는 상태"를 판정하는 자리가 한 곳에 남는다.
            assertThat(webhookChannel.isConfigured()).isFalse();
            assertThat(mailChannel.isConfigured()).isFalse();
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @Import(TestDatabaseGuard.class)
    @TestPropertySource(properties = {
            "stockscreener.alert.webhook.url=https://hooks.example.com/not-called",
            "stockscreener.alert.mail.from=bot@example.com"
    })
    @DisplayName("채널이 구성됐을 때")
    class WithChannels {

        @Autowired
        private AlertPort alertPort;

        @Test
        @DisplayName("팬아웃 포트가 AlertPort가 된다")
        void usesComposite() {
            // 값만 채웠을 뿐 실제 전송은 일어나지 않는다 — 조립 결과만 본다.
            assertThat(alertPort).isInstanceOf(CompositeAlertPort.class);
        }
    }
}
