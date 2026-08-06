package com.stockscreener.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기본 알림 구현이 <b>조용히 버리지 않는지</b>를 검증한다.
 *
 * <p>이 테스트가 하찮아 보이지만, no-op으로 두면 "알림이 갔다고 생각했는데 아무 데도 가지
 * 않은" 상태가 되고 그것은 배치 실패보다 늦게 발견된다. 실제 로그 출력을 잡아 확인한다.
 */
class LoggingAlertPortTest {

    private final LoggingAlertPort port = new LoggingAlertPort();
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoggingAlertPort.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("알림을 조용히 버리지 않고 로그로 남긴다")
    void doesNotSwallowAlerts() {
        port.send(AlertEvent.toOperator(
                AlertSeverity.WARNING, "일일 갱신 부분 실패", "850종목 중 2종목 실패", "daily:2026-08-05"));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("일일 갱신 부분 실패")
                .contains("850종목 중 2종목 실패")
                .contains("daily:2026-08-05");
    }

    @Test
    @DisplayName("심각도를 로그 레벨에 대응시킨다 — Phase 6의 Sentry가 레벨로 거른다")
    void mapsSeverityToLogLevel() {
        port.send(AlertEvent.toOperator(AlertSeverity.INFO, "완료", "", "k1"));
        port.send(AlertEvent.toOperator(AlertSeverity.WARNING, "부분 실패", "", "k2"));
        port.send(AlertEvent.toOperator(AlertSeverity.ERROR, "실패", "", "k3"));
        port.send(AlertEvent.toOperator(AlertSeverity.CRITICAL, "인증 실패", "", "k4"));

        assertThat(appender.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.INFO, Level.WARN, Level.ERROR, Level.ERROR);
    }

    @Test
    @DisplayName("사용자 대상 알림은 수신자를 남긴다 — 배치와 워치리스트가 같은 포트를 쓴다")
    void rendersRecipientForUserAlerts() {
        port.send(AlertEvent.toUser(42L, AlertSeverity.INFO, "조건 충족", "삼성전자", "wl:42:1"));

        assertThat(appender.list.get(0).getFormattedMessage()).contains("42");
    }

    @Test
    @DisplayName("부가 정보를 함께 남긴다 — 실행 id 없이는 어느 배치인지 알 수 없다")
    void rendersContext() {
        port.send(AlertEvent.toOperator(
                AlertSeverity.ERROR, "수집 실패", "본문", "key",
                Map.of("executionId", "17")));

        assertThat(appender.list.get(0).getFormattedMessage()).contains("executionId").contains("17");
    }

    @Test
    @DisplayName("제목 없는 알림은 만들 수 없다 — 읽히지 않는 알림은 없는 것과 같다")
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> AlertEvent.toOperator(AlertSeverity.INFO, "  ", "본문", "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }
}
