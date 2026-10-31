package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.IndicatorRegistry;
import com.stockscreener.indicator.IndicatorRetentionPolicy;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.indicator.IndicatorTradeDateIndex;
import com.stockscreener.indicator.PercentileCalculator;
import com.stockscreener.indicator.SectorRelativeCalculator;
import com.stockscreener.pipeline.collector.MarketCalendar;
import com.stockscreener.pipeline.collector.MarketIndexCalendar;
import com.stockscreener.pipeline.collector.PriceCalendar;
import com.stockscreener.pipeline.collector.PriceCollector;
import com.stockscreener.pipeline.collector.PriceGapRun;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.CollectedMarkets;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.watchlist.WatchlistAlertRunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 일일 갱신이 <b>시장 묶음 하나만</b> 도는가 — 사용자 결정 「시장별 분리」(2026-10-21 · 국내 13:30 · 미국 저녁).
 *
 * <p>대상 · 구멍 구간 · 지수 · 구멍 실패 닫기 넷이 묶음으로 걸러져야 한다. 하나라도 새면 국내 실행이 미국 봉을 잠정값으로 받거나(대상 ·
 * 구간), 미국 지수를 받거나(지수), 미국의 남은 구멍 실패를 매일 닫는다(닫기) — 넷 다 실행은 초록이다. 협력자는 목이고 잡 자신의 거름만
 * 본다 — 시장마다 판정이 갈리는 알림 · 기준일은 {@code PipelineEndToEndTest}가 실 DB로 잠근다.
 */
class DailyUpdateMarketsTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 10, 29);

    private final StockRegistrar stockRegistrar = mock(StockRegistrar.class);
    private final MarketIndexJob marketIndexJob = mock(MarketIndexJob.class);
    private final BatchExecutions batchExecutions = mock(BatchExecutions.class);
    private final BatchExecutionRecorder recorder = mock(BatchExecutionRecorder.class);
    private final BatchExecutionContext execution = mock(BatchExecutionContext.class);
    private final IndicatorRegistry indicatorRegistry = mock(IndicatorRegistry.class);
    private final UsSessionTarget usSessionTarget = mock(UsSessionTarget.class);
    private final WatchlistAlertRunner watchlist = mock(WatchlistAlertRunner.class);

    private DailyUpdateJob job;

    private final SecurityRef samsung = new SecurityRef(1L, "KOSPI", "005930");
    private final SecurityRef ecopro = new SecurityRef(2L, "KOSDAQ", "086520");
    private final SecurityRef apple = new SecurityRef(3L, "US", "AAPL");

    @BeforeEach
    void setUp() {
        job = new DailyUpdateJob(mock(PriceCollector.class), stockRegistrar, new MarketCalendar(),
                mock(PriceCalendar.class), mock(MarketIndexCalendar.class), marketIndexJob, batchExecutions,
                mock(IndicatorRunner.class), mock(PercentileCalculator.class), mock(SectorRelativeCalculator.class),
                mock(IndicatorRetentionPolicy.class), mock(IndicatorTradeDateIndex.class), watchlist, indicatorRegistry,
                mock(DerivedDayCalculator.class), usSessionTarget);
        when(batchExecutions.recorder()).thenReturn(recorder);
        when(batchExecutions.start(any(), any(), any(), anyInt(), any())).thenReturn(execution);
        when(batchExecutions.startRetry(any(), any(), anyInt(), anyLong())).thenReturn(execution);
        when(watchlist.run(any())).thenReturn(new WatchlistAlertRunner.Result(0, 0, 0, 0));
        when(stockRegistrar.activeUniverseMemberIds()).thenReturn(List.of(1L, 2L, 3L));
        when(stockRegistrar.toSecurityRefs(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return List.of(samsung, ecopro, apple).stream().filter(ref -> ids.contains(ref.stockId())).toList();
        });
        // 구멍 구간은 세 시장 모두에 있다 — 거름이 없으면 국내 실행이 AAPL 구간을 받는다.
        when(stockRegistrar.priceGapRuns(any(), any(), anyInt())).thenReturn(List.of(
                new PriceGapRun(1L, "KOSPI", "005930", true, false, TARGET.minusDays(40), TARGET.minusDays(40), 1),
                new PriceGapRun(3L, "US", "AAPL", true, false, TARGET.minusDays(40), TARGET.minusDays(40), 1)));
        Map<CollectionPhase, Set<Long>> unresolved = new EnumMap<>(CollectionPhase.class);
        unresolved.put(CollectionPhase.PRICE_GAP, new HashSet<>(Set.of(2L, 3L)));
        when(recorder.unresolvedStockPhases()).thenReturn(unresolved);
        when(marketIndexJob.targetIndexCodes()).thenReturn(List.of("KOSDAQ", "KOSPI", "US"));
    }

    @Test
    @DisplayName("묶음 둘의 합집합이 모으는 시장 전부다 — 시장을 더하고 묶음에 안 넣으면 어느 일일 갱신도 그 시장을 안 받는다")
    void marketGroupsCoverEveryCollectedMarket() {
        Set<String> union = new HashSet<>();
        for (DailyUpdateJob.Markets markets : DailyUpdateJob.Markets.values()) {
            assertThat(union).as("한 시장이 두 묶음에 들면 두 사슬이 같은 봉을 쓴다").doesNotContainAnyElementsOf(markets.codes());
            union.addAll(markets.codes());
        }
        assertThat(union).isEqualTo(CollectedMarkets.ALL);
        assertThat(DailyUpdateJob.Markets.ofJobName(DailyUpdateJob.JOB_NAME)).contains(DailyUpdateJob.Markets.DOMESTIC);
        assertThat(DailyUpdateJob.Markets.ofJobName(DailyUpdateJob.US_JOB_NAME)).contains(DailyUpdateJob.Markets.US);
        assertThat(DailyUpdateJob.Markets.ofJobName("corporate-action")).isEmpty();
    }

    @Test
    @DisplayName("국내 실행(daily-update)은 국내 종목 · 국내 구간 · 국내 지수만 받는다")
    void domesticRunTouchesOnlyDomesticMarkets() throws Exception {
        job.run(TARGET, BatchExecutionRecorder.TriggeredBy.SCHEDULER, DailyUpdateJob.Markets.DOMESTIC);

        verify(batchExecutions).start(eq(DailyUpdateJob.JOB_NAME), eq(TARGET), any(), eq(2), any());
        assertThat(processed(CollectionPhase.PRICE)).containsExactly(samsung, ecopro);
        assertThat(processed(CollectionPhase.PRICE_GAP)).containsExactly(samsung);
        verify(execution).plan(CollectionPhase.PRICE_GAP, 1);
        verify(marketIndexJob).collectInto(eq(execution), any(), eq(TARGET), eq(Set.of("KOSPI", "KOSDAQ")));
    }

    @Test
    @DisplayName("미국 실행(us-daily-update)은 미국 종목 · 미국 구간 · 미국 지수만 받는다")
    void usRunTouchesOnlyTheUs() throws Exception {
        job.run(TARGET, BatchExecutionRecorder.TriggeredBy.SCHEDULER, DailyUpdateJob.Markets.US);

        verify(batchExecutions).start(eq(DailyUpdateJob.US_JOB_NAME), eq(TARGET), any(), eq(1), any());
        assertThat(processed(CollectionPhase.PRICE)).containsExactly(apple);
        assertThat(processed(CollectionPhase.PRICE_GAP)).containsExactly(apple);
        verify(marketIndexJob).collectInto(eq(execution), any(), eq(TARGET), eq(Set.of("US")));
    }

    @Test
    @DisplayName("구멍이 사라진 실패는 그 실행의 시장 것만 닫는다 — 국내 실행이 미국의 남은 구멍 실패를 닫지 않는다")
    void vanishedGapFailuresAreClosedPerMarket() {
        // 미해결 PRICE_GAP: 086520(국내 · 구멍 없음)과 AAPL(미국 · 구멍 있음). 국내 실행에서 AAPL은 거른 구간 밖이라 「구멍 없음」으로 보인다.
        job.run(TARGET, BatchExecutionRecorder.TriggeredBy.SCHEDULER, DailyUpdateJob.Markets.DOMESTIC);

        verify(recorder).resolveFailures(2L, CollectionPhase.PRICE_GAP);
        verify(recorder, never()).resolveFailures(3L, CollectionPhase.PRICE_GAP);
    }

    @Test
    @DisplayName("대조군: 미국 실행은 미국의 남은 구멍 실패를 안 닫고, 국내 실패도 안 닫는다")
    void usRunClosesNeitherItsLiveGapNorDomesticFailures() {
        job.run(TARGET, BatchExecutionRecorder.TriggeredBy.SCHEDULER, DailyUpdateJob.Markets.US);

        verify(recorder, never()).resolveFailures(eq(2L), any());
        verify(recorder, never()).resolveFailures(eq(3L), any());
    }

    @Test
    @DisplayName("재실행은 실패한 실행의 잡 이름으로 남고 그 시장 종목만 다시 받는다")
    void retryStaysInItsMarket() throws Exception {
        when(recorder.unresolvedFailures(9L)).thenReturn(List.of(
                new StockFailure(1L, CollectionPhase.PRICE), new StockFailure(3L, CollectionPhase.PRICE)));

        job.retryFailures(9L, TARGET, DailyUpdateJob.Markets.US);

        verify(batchExecutions).startRetry(DailyUpdateJob.US_JOB_NAME, TARGET, 1, 9L);
        assertThat(processed(CollectionPhase.PRICE)).containsExactly(apple);
    }

    @Test
    @DisplayName("미국 대상 세션을 정하면 그 세션으로 미국만 받는다")
    void runUsUsesTheResolvedSession() {
        when(usSessionTarget.resolve(any())).thenReturn(new UsSessionTarget.Resolved(TARGET));

        DailyUpdateJob.UsRun run = job.runUs(Instant.parse("2026-10-30T12:00:00Z"), BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(run.targetDate()).isEqualTo(TARGET);
        verify(batchExecutions).start(eq(DailyUpdateJob.US_JOB_NAME), eq(TARGET), any(), eq(1), any());
    }

    @Test
    @DisplayName("IMPORTANT — 세션을 못 정하면 수집 없이 FAILED 행과 알림으로 끝난다 — 0건 성공이면 미국 봉이 멈춘 채 신선도까지 초록이다")
    void runUsFailsLoudlyWhenTheSessionIsUnknown() throws Exception {
        LocalDate expected = LocalDate.of(2026, 10, 29);
        when(usSessionTarget.resolve(any())).thenReturn(new UsSessionTarget.Unresolved("세션 달력(NYSE)이 낡았다", expected));

        DailyUpdateJob.UsRun run = job.runUs(Instant.parse("2026-10-30T12:00:00Z"), BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        assertThat(run.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.targetDate()).isNull();
        verify(batchExecutions).start(DailyUpdateJob.US_JOB_NAME, expected, BatchExecutionRecorder.TriggeredBy.SCHEDULER, 0,
                BatchExecutionRecorder.DeclaredScope.FULL);
        ArgumentCaptor<Throwable> cause = ArgumentCaptor.forClass(Throwable.class);
        verify(execution).failUnexpectedly(eq("대상 세션 판정"), cause.capture());
        assertThat(cause.getValue()).hasMessageContaining("낡았다");
        verify(stockRegistrar, never()).priceGapRuns(any(), any(), anyInt());
        verify(execution, never()).process(any(), any(), any());
    }

    private List<SecurityRef> processed(CollectionPhase phase) throws Exception {
        ArgumentCaptor<SecurityRef> refs = ArgumentCaptor.forClass(SecurityRef.class);
        verify(execution, atLeastOnce()).process(refs.capture(), eq(phase), any());
        return refs.getAllValues();
    }
}
