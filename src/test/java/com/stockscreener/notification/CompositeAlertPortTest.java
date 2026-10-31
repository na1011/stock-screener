package com.stockscreener.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팬아웃의 세 성질을 검증한다 — 격리, 무해, 그리고 <b>사라지지 않음</b>.
 *
 * <p>셋째가 이 클래스의 존재 이유다. 앞의 둘만 지키면 "아무 채널도 받지 않았는데 아무도
 * 모르는" 상태가 되고, 그것은 원래 알리려던 사건보다 늦게 발견된다.
 */
class CompositeAlertPortTest {

    private final List<AlertEvent> fallbackReceived = new ArrayList<>();
    private final AlertPort fallback = fallbackReceived::add;

    @Test
    @DisplayName("한 채널이 던져도 나머지 채널은 받는다")
    void isolatesChannelFailure() {
        RecordingChannel healthy = new RecordingChannel("healthy");
        ThrowingChannel broken = new ThrowingChannel("broken");
        AlertPort port = port(List.of(broken, healthy));

        port.send(AlertEvent.toOperator(AlertSeverity.ERROR, "제목", "본문", "k"));

        assertThat(healthy.received).hasSize(1);
        // 하나라도 나갔으면 폴백은 개입하지 않는다.
        assertThat(fallbackReceived).isEmpty();
    }

    @Test
    @DisplayName("채널 실패가 호출부로 올라가지 않는다 — 웹훅이 죽었다고 배치가 실패하면 안 된다")
    void neverThrowsToCaller() {
        AlertPort port = port(List.of(new ThrowingChannel("a"), new ThrowingChannel("b")));

        port.send(AlertEvent.toOperator(AlertSeverity.CRITICAL, "제목", "본문", "k"));

        // 예외가 나지 않았다는 것 자체가 단언이다. 그리고 사라지지도 않았다:
        assertThat(fallbackReceived).hasSize(1);
    }

    @Test
    @DisplayName("전부 실패하면 폴백이 받는다")
    void fallsBackWhenAllChannelsFail() {
        AlertPort port = port(List.of(new ThrowingChannel("a")));

        port.send(AlertEvent.toOperator(AlertSeverity.ERROR, "제목", "본문", "k"));

        assertThat(fallbackReceived).hasSize(1);
    }

    @Test
    @DisplayName("아무 채널도 받지 않으면 폴백이 받는다 — 임계에 걸려 조용히 사라지는 것을 막는다")
    void fallsBackWhenNoChannelAccepts() {
        RecordingChannel picky = new RecordingChannel("picky") {
            @Override
            public boolean accepts(AlertEvent event) {
                return false;
            }
        };
        AlertPort port = port(List.of(picky));

        port.send(AlertEvent.toOperator(AlertSeverity.INFO, "제목", "본문", "k"));

        assertThat(picky.received).isEmpty();
        assertThat(fallbackReceived).hasSize(1);
    }

    @Test
    @DisplayName("대상 아님은 INFO, 받고 실패함은 WARN — 두 사건을 한 줄로 찍지 않는다")
    void separatesNotAcceptedFromFailed() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(CompositeAlertPort.class);
        logger.addAppender(logs);
        try {
            RecordingChannel picky = new RecordingChannel("picky") {
                @Override
                public boolean accepts(AlertEvent event) {
                    return false;
                }
            };
            port(List.of(picky)).send(AlertEvent.toOperator(AlertSeverity.INFO, "제목", "본문", "a"));
            port(List.of(new ThrowingChannel("broken")))
                    .send(AlertEvent.toOperator(AlertSeverity.ERROR, "제목", "본문", "b"));

            assertThat(logs.list).filteredOn(e -> e.getMessage().contains("임계에도 안 걸린다"))
                    .singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.INFO);
            assertThat(logs.list).filteredOn(e -> e.getMessage().contains("전부 전송에 실패했다"))
                    .singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);

            // 사용자 알림인데 받는 채널이 없으면 정상이 아니다 — 경고로 남는다.
            port(List.of(picky)).send(AlertEvent.toUser(42L, AlertSeverity.INFO, "제목", "본문", "c"));
            assertThat(logs.list).filteredOn(e -> e.getMessage().contains("사용자 알림을 받을 채널이 없다"))
                    .singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("억제된 알림은 채널에도 폴백에도 가지 않는다")
    void suppressedEventGoesNowhere() {
        RecordingChannel channel = new RecordingChannel("c");
        AlertPort port = new CompositeAlertPort(
                List.of(channel), new AlertSuppressor(Duration.ofMinutes(10)), fallback);

        AlertEvent event = AlertEvent.toOperator(AlertSeverity.ERROR, "제목", "본문", "same-key");
        port.send(event);
        port.send(event);

        assertThat(channel.received).hasSize(1);
        // 억제는 "이미 보냈다"이지 "못 보냈다"가 아니다. 폴백이 받으면 억제한 의미가 없다.
        assertThat(fallbackReceived).isEmpty();
    }

    /** 억제 창을 0으로 두어 억제를 끈 팬아웃. 억제 자체는 별도 테스트가 본다. */
    private AlertPort port(List<AlertChannel> channels) {
        return new CompositeAlertPort(channels, new AlertSuppressor(Duration.ZERO), fallback);
    }

    private static class RecordingChannel implements AlertChannel {
        private final String name;
        private final List<AlertEvent> received = new ArrayList<>();

        RecordingChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean accepts(AlertEvent event) {
            return true;
        }

        @Override
        public void deliver(AlertEvent event) {
            received.add(event);
        }
    }

    private static class ThrowingChannel extends RecordingChannel {
        ThrowingChannel(String name) {
            super(name);
        }

        @Override
        public void deliver(AlertEvent event) {
            throw new IllegalStateException("채널이 죽었다");
        }
    }
}
