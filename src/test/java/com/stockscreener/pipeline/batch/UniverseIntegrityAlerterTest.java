package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.MembershipGap;
import com.stockscreener.pipeline.collector.StockRegistrar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 알림이 <b>언제 다시 오는가</b>.
 *
 * <p>결손은 고칠 때까지 계속 참이라, 억제가 없으면 며칠 안에 아무도 이 알림을 보지 않게 된다.
 * 반대로 억제 단위가 너무 굵으면 <b>한 종목의 한 축을 알린 것이 다른 종목의 다른 축을 덮는다</b> —
 * 그래서 키가 (축, 종목)이고, 그것이 {@code JobFreshnessAlerter}(잡 이름당)와 합치지 않은 이유다.
 */
class UniverseIntegrityAlerterTest {

    /** 테스트가 시간을 미는 시계. {@code Clock.fixed}는 못 민다. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-13T00:00:00Z");

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private StockRegistrar stockRegistrar;
    private AlertPort alertPort;
    private MovableClock clock;
    private UniverseIntegrityAlerter alerter;

    @BeforeEach
    void setUp() {
        stockRegistrar = mock(StockRegistrar.class);
        alertPort = mock(AlertPort.class);
        clock = new MovableClock();
        alerter = new UniverseIntegrityAlerter(stockRegistrar, alertPort, clock);
    }

    @Test
    @DisplayName("결손이 없으면 안 보낸다")
    void staysQuietWhenNothingIsMissing() {
        when(stockRegistrar.membershipGaps()).thenReturn(List.of());

        assertThat(alerter.evaluateAndAlert()).isFalse();
        verify(alertPort, never()).send(any());
    }

    @Test
    @DisplayName("면제만 있으면 안 보낸다 — 돌려도 안 닫히는 것을 매주 통지하면 그 알림은 곧 무시된다")
    void staysQuietWhenEverythingIsExempt() {
        when(stockRegistrar.membershipGaps()).thenReturn(List.of(exempt("950260")));

        assertThat(alerter.evaluateAndAlert()).isFalse();
        verify(alertPort, never()).send(any());
    }

    @Test
    @DisplayName("결손이 있으면 보내고 본문이 닫는 명령을 함께 적는다")
    void sendsWithTheCommandThatClosesIt() {
        when(stockRegistrar.membershipGaps())
                .thenReturn(List.of(gap("005930", "KOSPI", MembershipGap.Axis.PRICE_HISTORY)));

        assertThat(alerter.evaluateAndAlert()).isTrue();
        AlertEvent event = captureAlert();
        assertThat(event.body()).contains("005930").contains("--job=initial-load");
    }

    @Test
    @DisplayName("영업활동현금흐름의 미국 갈래에는 닫는 명령을 적지 않는다 — full-statement-refill에 US 소스가 없다")
    void doesNotPrintACommandThatCannotClose() {
        when(stockRegistrar.membershipGaps())
                .thenReturn(List.of(gap("MUR", "US", MembershipGap.Axis.CASH_FLOW)));

        alerter.evaluateAndAlert();

        AlertEvent event = captureAlert();
        assertThat(event.body()).contains("MUR").contains("닫는 명령이 없다");
        assertThat(event.body()).doesNotContain("full-statement-refill --tickers=MUR");
    }

    @Test
    @DisplayName("같은 (축, 종목)은 7일 안에 다시 안 보낸다")
    void suppressesTheSameAxisAndStockForAWeek() {
        when(stockRegistrar.membershipGaps())
                .thenReturn(List.of(gap("005930", "KOSPI", MembershipGap.Axis.PRICE_HISTORY)));

        assertThat(alerter.evaluateAndAlert()).isTrue();
        clock.advance(Duration.ofDays(6));
        assertThat(alerter.evaluateAndAlert()).isFalse();
        clock.advance(Duration.ofDays(1));
        assertThat(alerter.evaluateAndAlert()).isTrue();
        verify(alertPort, times(2)).send(any());
    }

    @Test
    @DisplayName("한 종목의 한 축을 알린 것이 다른 축을 덮지 않는다 — 억제 키가 굵으면 결손이 조용히 사라진다")
    void suppressionIsPerAxisNotPerStock() {
        when(stockRegistrar.membershipGaps())
                .thenReturn(List.of(gap("005930", "KOSPI", MembershipGap.Axis.PRICE_HISTORY)))
                .thenReturn(List.of(gap("005930", "KOSPI", MembershipGap.Axis.FINANCIAL)));

        assertThat(alerter.evaluateAndAlert()).isTrue();
        assertThat(alerter.evaluateAndAlert()).isTrue();
    }

    @Test
    @DisplayName("발화할 때 면제된 것을 이름으로 함께 적는다 — 면제 목록이 낡았는지는 그 줄로만 드러난다")
    void namesTheExemptOnesWhenItFires() {
        when(stockRegistrar.membershipGaps()).thenReturn(List.of(
                gap("005930", "KOSPI", MembershipGap.Axis.PRICE_HISTORY), exempt("950260")));

        alerter.evaluateAndAlert();

        assertThat(captureAlert().body()).contains("면제").contains("950260");
    }

    private AlertEvent captureAlert() {
        ArgumentCaptor<AlertEvent> event = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertPort, times(1)).send(event.capture());
        return event.getValue();
    }

    private static MembershipGap gap(String ticker, String market, MembershipGap.Axis axis) {
        Set<MembershipGap.Axis> missing = EnumSet.of(axis);
        return new MembershipGap(1L, market, ticker, ticker + " 종목",
                missing, EnumSet.noneOf(MembershipGap.Axis.class));
    }

    private static MembershipGap exempt(String ticker) {
        return new MembershipGap(2L, "KOSDAQ", ticker, ticker + " 종목",
                EnumSet.noneOf(MembershipGap.Axis.class),
                EnumSet.of(MembershipGap.Axis.CASH_FLOW));
    }
}
