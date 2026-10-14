package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.collector.UniverseReconciler;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 리밸런싱 잡이 <b>끝에 결손 보충 꼬리를 부르는지</b>를 고정한다.
 *
 * <p>이 단언이 2026-09-18까지 {@code PipelineSchedulerChainTest}에 있었고, 그래서
 * <b>스케줄러 경로에서만</b> 참이었다 — 실측으로 리밸런싱 15회 중 손 9·관리자 2가 꼬리를
 * 안 탔고 그 11회의 신규 편입은 원자료가 빈 채로 유니버스에 들어왔다. 꼬리를 잡 안으로
 * 옮기면서 이 시험도 함께 옮겼다. 여기 있으면 어느 호출 경로든 같은 보장을 받는다.
 */
class UniverseRebalanceTailTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 19);

    private JdbcTemplate jdbcTemplate;
    private BatchExecutions batchExecutions;
    private BatchExecutionContext execution;
    private MembershipGapFiller membershipGapFiller;
    private FreshnessCatchUpJob freshnessCatchUp;
    private UniverseRebalanceJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        batchExecutions = mock(BatchExecutions.class);
        execution = mock(BatchExecutionContext.class);
        membershipGapFiller = mock(MembershipGapFiller.class);
        freshnessCatchUp = mock(FreshnessCatchUpJob.class);
        when(batchExecutions.start(anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(execution);
        when(execution.finishQuietly()).thenReturn(BatchStatus.SUCCESS);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        job = new UniverseRebalanceJob(mock(DataSourceRegistry.class),
                mock(UniverseReconciler.class), mock(StockRegistrar.class), batchExecutions,
                mock(AlertPort.class), jdbcTemplate, membershipGapFiller, freshnessCatchUp);
    }

    @Test
    @DisplayName("리밸런싱이 끝나면 꼬리가 결손을 채운다 — 편입이 바뀐 직후가 결손이 생기는 유일한 순간이다")
    void fillsMembershipGapsAfterRebalancing() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.ADMIN);

        verify(membershipGapFiller).fillAndAlert(eq(AS_OF),
                eq(BatchExecutionRecorder.TriggeredBy.ADMIN));
    }

    @Test
    @DisplayName("꼬리는 실행 행을 마감한 뒤다 — 앞에 두면 이 잡이 꼬리 내내 RUNNING으로 남아 ops가 「도는 배치」로 읽는다")
    void runsTheTailAfterClosingTheExecutionRow() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER);

        InOrder order = inOrder(execution, membershipGapFiller, freshnessCatchUp);
        order.verify(execution).finishQuietly();
        order.verify(membershipGapFiller).fillAndAlert(any(), any());
        order.verify(freshnessCatchUp).runAfterRebalance(BatchExecutionRecorder.TriggeredBy.SCHEDULER);
    }

    /**
     * 던지는 자리가 <b>지수 루프 앞의 {@code plan()}</b>이다 — 루프 안의
     * {@code abortIfAbandoned()}를 쓰려면 대상이 하나 필요한데 {@code UniverseTarget}이
     * private record라 밖에서 못 만든다. 고정하려는 것은 <b>「run()이 종료로 던지면 꼬리가
     * 안 돈다」</b>이고 그 성질은 던지는 자리와 무관하다.
     */
    @Test
    @DisplayName("종료로 중단되면 꼬리를 안 돈다 — 덜 반영된 명부로 재면 곧 편출될 종목에 콜을 태운다")
    void skipsTheTailWhenTheRebalancingWasAborted() {
        doThrow(new BatchShutdownException("종료 중이다"))
                .when(execution).plan(any(), org.mockito.ArgumentMatchers.anyInt());

        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.SCHEDULER))
                .isInstanceOf(BatchShutdownException.class);

        verifyNoInteractions(membershipGapFiller, freshnessCatchUp);
    }
}
