package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.GovernanceFactDeriver;
import com.stockscreener.pipeline.collector.StockRegistrar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 거버넌스 유도의 <b>기본 모집단</b>을 고정한다.
 *
 * <p>매 평일 화해가 보존 창 편입 전원의 지표를 다시 쓰므로, 그 입력인 거버넌스 사실도 같은
 * 모집단으로 유도돼야 한다. 지금 편입만 유도하면 과거 구성원은 지표가 새 코드로 써지는데 입력은
 * 옛 유도에 멈춘 채 남고, <b>어느 쪽도 에러를 내지 않는다.</b> 술어 자체는
 * {@code StockRegistrarLookupTest}가 실 DB로 고정한다 — 여기는 그 술어를 고르는 갈래만 본다.
 */
class GovernanceDeriveJobTest {

    private StockRegistrar stockRegistrar;
    private GovernanceDeriveJob job;

    @BeforeEach
    void setUp() {
        stockRegistrar = mock(StockRegistrar.class);
        BatchExecutions batchExecutions = mock(BatchExecutions.class);
        BatchExecutionContext execution = mock(BatchExecutionContext.class);
        when(batchExecutions.start(anyString(), any(), any(), anyInt(), any())).thenReturn(execution);
        when(execution.finish()).thenReturn(BatchStatus.SUCCESS);
        when(stockRegistrar.toSecurityRefs(any())).thenReturn(List.of());
        job = new GovernanceDeriveJob(mock(GovernanceFactDeriver.class), stockRegistrar, batchExecutions);
    }

    @Test
    @DisplayName("IMPORTANT — 티커를 안 주면 보존 창 편입을 유도한다 — 화해의 모집단과 같아야 한다")
    void defaultPopulationIsTheRetentionWindow() {
        job.run(LocalDate.of(2026, 10, 2), BatchExecutionRecorder.TriggeredBy.SCHEDULER, List.of());

        verify(stockRegistrar).windowMemberIds();
        verify(stockRegistrar, never()).activeUniverseMemberIds();
    }

    @Test
    @DisplayName("티커를 주면 그 티커의 창 편입만 — 손으로 고른 범위를 넓히지 않는다")
    void tickersNarrowWithinTheWindow() {
        when(stockRegistrar.activeUniverseMemberIdsByTicker(List.of("005930"))).thenReturn(List.of(1L));

        job.run(LocalDate.of(2026, 10, 2), BatchExecutionRecorder.TriggeredBy.MANUAL, List.of("005930"));

        verify(stockRegistrar).activeUniverseMemberIdsByTicker(List.of("005930"));
        verify(stockRegistrar, never()).windowMemberIds();
    }
}
