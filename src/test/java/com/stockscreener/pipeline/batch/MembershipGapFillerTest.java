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
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private FullStatementRefillJob fullStatementRefillJob;
    private GovernanceLoadJob governanceLoadJob;
    private GovernanceDeriveJob governanceDeriveJob;
    private UniverseIntegrityAlerter alerter;
    private AlertPort alertPort;
    private BatchExecutionReader batchExecutionReader;
    private MembershipGapFiller filler;

    @BeforeEach
    void setUp() {
        stockRegistrar = mock(StockRegistrar.class);
        initialLoadJob = mock(InitialLoadJob.class);
        companyProfileJob = mock(CompanyProfileJob.class);
        fullStatementRefillJob = mock(FullStatementRefillJob.class);
        governanceLoadJob = mock(GovernanceLoadJob.class);
        governanceDeriveJob = mock(GovernanceDeriveJob.class);
        alerter = mock(UniverseIntegrityAlerter.class);
        alertPort = mock(AlertPort.class);
        batchExecutionReader = mock(BatchExecutionReader.class);
        when(batchExecutionReader.liveJobNames()).thenReturn(List.of());
        filler = new MembershipGapFiller(stockRegistrar, initialLoadJob, companyProfileJob,
                fullStatementRefillJob, governanceLoadJob, governanceDeriveJob,
                alerter, alertPort, batchExecutionReader,
                org.mockito.Mockito.mock(KrXbrlCollectJob.class), null, false);
    }

    @Test
    @DisplayName("복원 꼬리 — 닫힌 창 편입의 시세 결손을 리밸런싱 꼬리와 같은 인자(7년 · 시세만 · GAP_FILL)로 받고, 다시 재서 남은 것을 낸다")
    void restoredPriceGapsAreLoadedWithTheSameRangeAsTheRebalanceTail() {
        when(stockRegistrar.windowClosedMembersWithoutBars())
                .thenReturn(List.of("FWONA", "STZ", "V"))
                .thenReturn(List.of("FWONA"));

        List<String> left = filler.fillRestoredPriceGaps(AS_OF);

        ArgumentCaptor<InitialLoadJob.LoadRange> range = ArgumentCaptor.forClass(InitialLoadJob.LoadRange.class);
        verify(initialLoadJob).run(eq(AS_OF), eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), range.capture());
        assertThat(range.getValue().tickers()).containsExactly("FWONA", "STZ", "V");
        assertThat(range.getValue().priceYears()).isEqualTo(MembershipGapFiller.PRICE_YEARS);
        assertThat(range.getValue().financialYears()).isZero();
        assertThat(left).as("받은 뒤에도 남은 것을 돌려줘야 호출자가 삼키지 않는다").containsExactly("FWONA");
    }

    @Test
    @DisplayName("복원 꼬리 — 결손이 0이면 적재를 안 부른다(빈 티커는 전량 적재다)")
    void noRestoredGapMeansNoLoad() {
        when(stockRegistrar.windowClosedMembersWithoutBars()).thenReturn(List.of());

        assertThat(filler.fillRestoredPriceGaps(AS_OF)).isEmpty();
        verifyNoInteractions(initialLoadJob);
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
                eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), ranges.capture());

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
    @DisplayName("IMPORTANT — 업종(결산월 · SIC)을 재무 적재보다 먼저 받는다 — 재무 어댑터가 그 둘을 팩트 해석의 증인으로 묻는다(IssuerProfile)")
    void companyProfileRunsBeforeTheFinancialLoad() {
        given(gap("035420", MembershipGap.Axis.FINANCIAL));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        InOrder order = inOrder(companyProfileJob, initialLoadJob);
        order.verify(companyProfileJob).run(any(), any(), any(), any());
        order.verify(initialLoadJob).run(any(), any(), any());
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
    @DisplayName("꼬리의 업종 호출은 FULL이다 — 리밸런싱 꼬리가 업종 잡의 유일한 정기 경로다(ADR-0172)")
    @SuppressWarnings("unchecked")
    void companyProfileFromTheTailIsFullScope() {
        given();

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        // 인자를 잡아 잡의 규칙에 먹인다 — NARROW로 찍히면 그 SCHEDULER 행이 시계에서 빠져 업종 잡이 연체로 뜬다.
        org.mockito.ArgumentCaptor<List<String>> tickers = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.ArgumentCaptor<String> universe = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(companyProfileJob).run(eq(AS_OF), eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER),
                tickers.capture(), universe.capture());
        assertThat(CompanyProfileJob.scopeOf(tickers.getValue(), universe.getValue()))
                .isEqualTo(BatchExecutionRecorder.DeclaredScope.FULL);
    }

    @Test
    @DisplayName("다른 배치가 돌고 있으면 적재를 건너뛰고 알린다 — 스케줄러 풀 1이 관리자·CLI 경로를 원리적으로 못 본다")
    void skipsTheLoadWhileAnotherBatchIsRunning() {
        when(batchExecutionReader.liveJobNames()).thenReturn(List.of("daily-update"));
        given(gap("005930", MembershipGap.Axis.PRICE_HISTORY));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.ADMIN);

        verify(initialLoadJob, never()).run(any(), any(), any());
        AlertEvent alert = captureAlert();
        assertThat(alert.severity()).isEqualTo(AlertSeverity.WARNING);
        // 막힌 것과 할 일이 없던 것이 구분돼야 한다 — 어느 잡 때문인지까지 싣는다.
        assertThat(alert.body()).contains("daily-update");
    }

    @Test
    @DisplayName("겹쳐서 적재를 건너뛴 주에도 업종은 부른다 — price_daily에 안 쓰는 잡까지 막으면 가짜 연체가 또 하나 생긴다")
    void stillCallsCompanyProfileWhenTheLoadWasBlocked() {
        when(batchExecutionReader.liveJobNames()).thenReturn(List.of("daily-update"));
        given(gap("005930", MembershipGap.Axis.PRICE_HISTORY));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.ADMIN);

        verify(companyProfileJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.ADMIN), eq(List.of()), eq(null));
    }

    @Test
    @DisplayName("도는 배치가 없으면 그대로 돈다 — 게이트가 상시로 막으면 꼬리가 영영 안 돈 채 초록이 된다")
    void runsNormallyWhenNothingElseIsRunning() {
        when(batchExecutionReader.liveJobNames()).thenReturn(List.of());
        given(gap("005930", MembershipGap.Axis.PRICE_HISTORY));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(initialLoadJob).run(any(), any(), any());
        verify(companyProfileJob).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("상한을 넘으면 적재를 안 한다 — 그 크기는 리밸런싱이 아니라 유니버스를 다시 그은 것이다")
    void alertsInsteadOfLoadingWhenOverTheLimit() {
        given(overTheLimit());

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(initialLoadJob, never()).run(any(), any(), any());
        assertThat(captureAlert().severity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("거버넌스 결손을 적재+유도 한 쌍으로 채운다 — 적재만 하면 팩트가 안 바뀌는데 둘 다 성공이다")
    void fillsGovernanceGapWithLoadThenDerive() {
        when(stockRegistrar.membershipGaps()).thenReturn(List.of(
                gap("KOSPI", "005930", MembershipGap.Axis.GOVERNANCE)));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        InOrder order = inOrder(governanceLoadJob, governanceDeriveJob);
        // span(넷째 인자)은 null이다 — 표마다 필요한 해 수가 다른데 스칼라로 덮으면 나머지가 좁아진다.
        order.verify(governanceLoadJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), isNull(), isNull(),
                eq(List.of()), eq(List.of("005930")));
        order.verify(governanceDeriveJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), eq(List.of("005930")));
    }

    @Test
    @DisplayName("거버넌스 결손이 없으면 아예 안 부른다 — 빈 티커는 resolveTargets에서 활성 편입 전량이 된다")
    void doesNotCallGovernanceWhenThereIsNoGap() {
        when(stockRegistrar.membershipGaps()).thenReturn(List.of(
                gap("KOSPI", "005930", MembershipGap.Axis.PRICE_HISTORY)));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verifyNoInteractions(governanceLoadJob);
        verifyNoInteractions(governanceDeriveJob);
    }

    @Test
    @DisplayName("거버넌스가 상한을 넘겨도 시세 적재는 돈다 — 합집합에 넣으면 거버넌스가 시세를 막는다")
    void governanceLimitDoesNotBlockPriceLoad() {
        List<MembershipGap> gaps = new java.util.ArrayList<>();
        gaps.add(gap("KOSPI", "005930", MembershipGap.Axis.PRICE_HISTORY));
        for (int i = 0; i < 201; i++) {
            gaps.add(gap("KOSPI", "G%05d".formatted(i), MembershipGap.Axis.GOVERNANCE));
        }
        when(stockRegistrar.membershipGaps()).thenReturn(List.copyOf(gaps));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(initialLoadJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), any());
        verifyNoInteractions(governanceLoadJob);
    }

    @Test
    @DisplayName("좁힌 적재는 GAP_FILL로, 전량인 업종은 물려받은 트리거로 기록한다 — 한 실행 안에서 갈린다")
    void narrowedLoadsAreRecordedAsGapFillWhileFullScopeKeepsTheTrigger() {
        // 이 비대칭이 JobFreshnessPolicy의 불변식(「스케줄러 실행은 언제나 전량이다」)을 세운다.
        // 둘을 같게 만들면 꼬리의 3종목짜리 실행이 월 1회 전량 잡의 시계를 되돌리는데,
        // 증상이 「연체 경보가 안 뜨는 것」이라 어디에도 안 붉는다.
        when(stockRegistrar.membershipGaps()).thenReturn(List.of(
                gap("KOSPI", "005930", MembershipGap.Axis.PRICE_HISTORY)));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(initialLoadJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), any());
        verify(companyProfileJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), eq(List.of()), eq(null));
    }

    @Test
    @DisplayName("상한을 넘긴 주에도 업종은 부른다 — 결손이 있는 주에만 부르면 신선도가 SCHEDULER 전용으로 갈아탄 뒤 가짜 연체가 뜬다")
    void stillCallsCompanyProfileWhenTheLoadIsOverTheLimit() {
        given(overTheLimit());

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        verify(companyProfileJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.SCHEDULER), eq(List.of()), eq(null));
    }

    private static MembershipGap[] overTheLimit() {
        List<MembershipGap> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(gap("T%03d".formatted(i), MembershipGap.Axis.PRICE_HISTORY));
        }
        return many.toArray(new MembershipGap[0]);
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

    @Test
    @DisplayName("영업활동현금흐름 결손을 전체 재무제표 보정으로 채운다 — 문장만 내면 영영 안 닫힌다")
    void fillsCashFlowGapWithFullStatementRefill() {
        given(gap("001820", MembershipGap.Axis.CASH_FLOW));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        // MUST: 분기를 함께 받는다(다섯째 인자). FY만 채우면 Q4 유도가 안 따라가
        // 재고자산이 그 자리에서 새 결손이 된다(ADR-0069).
        verify(fullStatementRefillJob).run(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.GAP_FILL), isNull(),
                eq(List.of("001820")), eq(true), eq(false));
    }

    @Test
    @DisplayName("미국 영업활동현금흐름 결손에는 안 부른다 — 그 잡은 DART 전용이라 한 행도 못 쓴다")
    void doesNotRefillStatementsForUsMarket() {
        given(gap("US", "GLNG", MembershipGap.Axis.CASH_FLOW));

        filler.fillAndAlert(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        // 이 음성이 없으면 위 양성은 「술어가 늘 참」이어도 통과한다.
        verify(fullStatementRefillJob, never()).run(any(), any(), any(), anyList(),
                anyBoolean(), anyBoolean());
    }

    private static MembershipGap gap(String ticker, MembershipGap.Axis axis) {
        return gap("KOSPI", ticker, axis);
    }

    private static MembershipGap gap(String market, String ticker, MembershipGap.Axis axis) {
        Set<MembershipGap.Axis> missing = EnumSet.of(axis);
        return new MembershipGap(1L, market, ticker, ticker + " 주식회사",
                missing, EnumSet.noneOf(MembershipGap.Axis.class));
    }
}
