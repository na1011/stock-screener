package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.MembershipGap;
import com.stockscreener.pipeline.collector.StockRegistrar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 리밸런싱 꼬리가 <b>무엇을 부르고 무엇을 안 부르는가</b>.
 *
 * <p>여기서 지키는 것은 성능이 아니라 <b>부작용의 경계</b>다 — 한 번에 부르면 정정본이 쌓여
 * 지표가 지워지고, 상한 없이 부르면 유니버스를 다시 그은 날 시간 단위 작업이 자동으로 시작되며,
 * 던지면 리밸런싱 성공이 예외에 묻힌다.
 */
class MembershipGapFillerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 11);

    private StockRegistrar stockRegistrar;
    private InitialLoadJob initialLoadJob;
    private CompanyProfileJob companyProfileJob;
    private UniverseIntegrityAlerter alerter;
    private AlertPort alertPort;
    private MembershipGapFiller filler;

    @BeforeEach
    void setUp() {
        stockRegistrar = mock(StockRegistrar.class);
        initialLoadJob = mock(InitialLoadJob.class);
        companyProfileJob = mock(CompanyProfileJob.class);
        alerter = mock(UniverseIntegrityAlerter.class);
        alertPort = mock(AlertPort.class);
        filler = new MembershipGapFiller(stockRegistrar, initialLoadJob, companyProfileJob,
                alerter, alertPort);
    }

    @Test
    @DisplayName("축별로 갈라 부른다 — 한 번에 부르면 재무가 있는 종목에 정정본이 쌓여 지표가 지워진다")
    void splitsTheLoadByAxis() {
        given(gap("005930", MembershipGap.Axis.PRICE_HISTORY),
                gap("035420", MembershipGap.Axis.FINANCIAL));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        ArgumentCaptor<InitialLoadJob.LoadRange> ranges =
                ArgumentCaptor.forClass(InitialLoadJob.LoadRange.class);
        verify(initialLoadJob, times(2)).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), ranges.capture());

        InitialLoadJob.LoadRange price = ranges.getAllValues().get(0);
        assertThat(price.tickers()).containsExactly("005930");
        assertThat(price.skipFinancial()).isTrue();
        // MUST: 기본값 2년이 아니다. 2년은 약 490봉인데 읽기 창이 그보다 훨씬 길어서,
        // 기본값으로 채우면 창이 긴 지표가 안 나는 종목을 매주 새로 만든다.
        assertThat(price.priceYears()).isGreaterThan(2);

        InitialLoadJob.LoadRange financial = ranges.getAllValues().get(1);
        assertThat(financial.tickers()).containsExactly("035420");
        assertThat(financial.skipPrice()).isTrue();
    }

    @Test
    @DisplayName("업종은 티커를 주지 않고 결손이 0이어도 부른다 — 티커를 주면 업종 유무를 아예 안 본다")
    void callsCompanyProfileWithoutTickersEvenWhenNothingIsMissing() {
        given();

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(companyProfileJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), eq(List.of()), eq(null));
        verify(initialLoadJob, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("상한을 넘으면 돌리지 않고 알린다 — 그 크기는 리밸런싱이 아니라 유니버스를 다시 그은 것이다")
    void alertsInsteadOfLoadingWhenOverTheLimit() {
        List<MembershipGap> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(gap("T%03d".formatted(i), MembershipGap.Axis.PRICE_HISTORY));
        }
        given(many.toArray(new MembershipGap[0]));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(initialLoadJob, never()).run(any(), any(), any());
        verify(companyProfileJob, never()).run(any(), any(), any(), any());
        assertThat(captureAlert().severity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("채운 뒤 다시 재서 알린다 — 채운 것을 그대로 닫혔다고 보고하면 소스가 안 준 경우가 성공으로 접힌다")
    void remeasuresAfterFillingAndAlertsWithThat() {
        MembershipGap before = gap("005930", MembershipGap.Axis.PRICE_HISTORY);
        MembershipGap after = gap("005930", MembershipGap.Axis.PRICE_HISTORY);
        when(stockRegistrar.membershipGaps()).thenReturn(List.of(before), List.of(after));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(stockRegistrar, times(2)).membershipGaps();
        verify(alerter).alert(List.of(after));
    }

    @Test
    @DisplayName("꼬리가 터져도 위로 안 올린다 — 예외가 @Scheduled 밖으로 나가면 리밸런싱까지 실패한 것으로 기록된다")
    void neverThrowsSoTheRebalancingStaysSuccessful() {
        given(gap("005930", MembershipGap.Axis.PRICE_HISTORY));
        doThrow(new IllegalStateException("소스가 죽었다"))
                .when(initialLoadJob).run(any(), any(), any());

        assertThatCode(() -> filler.fillAndAlert(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).doesNotThrowAnyException();

        // MUST NOT: 삼키지 않는다. 채우기가 실패한 것과 채울 것이 없던 것은 다르다.
        assertThat(captureAlert().severity()).isEqualTo(AlertSeverity.ERROR);
    }

    @Test
    @DisplayName("종료 신호는 실패가 아니다 — 알리지 않고 다음 점검에 맡긴다")
    void doesNotAlertOnShutdown() {
        given(gap("005930", MembershipGap.Axis.PRICE_HISTORY));
        doThrow(new BatchShutdownException("종료 중이다"))
                .when(initialLoadJob).run(any(), any(), any());

        assertThatCode(() -> filler.fillAndAlert(
                AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER)).doesNotThrowAnyException();

        verify(alertPort, never()).send(any());
    }

    private void given(MembershipGap... gaps) {
        when(stockRegistrar.membershipGaps()).thenReturn(List.of(gaps));
    }

    private AlertEvent captureAlert() {
        ArgumentCaptor<AlertEvent> event = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertPort).send(event.capture());
        return event.getValue();
    }

    private static MembershipGap gap(String ticker, MembershipGap.Axis axis) {
        Set<MembershipGap.Axis> missing = EnumSet.of(axis);
        return new MembershipGap(1L, "KOSPI", ticker, ticker + " 주식회사",
                missing, EnumSet.noneOf(MembershipGap.Axis.class));
    }
}
