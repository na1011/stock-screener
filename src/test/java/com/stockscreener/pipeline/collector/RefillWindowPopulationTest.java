package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.batch.BatchExecutionContext;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder;
import com.stockscreener.pipeline.batch.BatchExecutions;
import com.stockscreener.pipeline.batch.CompanyProfileJob;
import com.stockscreener.pipeline.datasource.SecurityRef;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관문 손 재적재의 미국 모집단 — 활성 편입 없음 ∧ 시대가 보존 창과 겹침(탐색 {@code US-PAST-LISTING-IMPL-1018.md} U6). 오늘 후보보다 넓다 —
 * 닫힌 과거 구성원과 들인 폐지 실체 행이 그 시대 안 날짜의 과거 명부 후보라 재무가 필요하다.
 *
 * <p><b>대조군</b>: 오늘 후보({@link StockRegistrar#usRankingCandidateTickers})는 닫힌 행을 안 낸다 — 그 술어로 관문 재적재를 돌면 과거 명부의 닫힌
 * 행에 재무가 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class RefillWindowPopulationTest {

    private static final LocalDate WINDOW_START = LocalDate.of(2025, 10, 10);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StockRegistrar registrar;

    @Test
    void closedRowsWhoseEraOverlapsTheWindowAreInTheRefillPopulation() {
        row("RWOPEN", null);
        row("RWCLOSED~2026-03-01", LocalDate.of(2026, 3, 2));
        row("RWGONE~2025-06-30", LocalDate.of(2025, 7, 1));

        List<String> window = registrar.usRefillWindowTickers(WINDOW_START);
        List<String> today = registrar.usRankingCandidateTickers();

        assertThat(window).contains("RWOPEN", "RWCLOSED~2026-03-01").doesNotContain("RWGONE~2025-06-30");
        assertThat(today).as("대조군 — 오늘 후보는 닫힌 행을 안 낸다").contains("RWOPEN").doesNotContain("RWCLOSED~2026-03-01");
        assertThat(registrar.usRefillWindowIdsByTicker(List.of("RWCLOSED~2026-03-01", "RWGONE~2025-06-30"), WINDOW_START)).hasSize(1);
    }

    /**
     * 후보 모드 손 FR의 업종 먼저 단계도 같은 모집단을 푼다 — 오늘 후보 술어로 풀던 때 R-D4b 작은 DB 드라이런(2026-10-18)에서 들인 닫힌 행 일곱이
     * 전부 「후보가 하나도 없다」로 거절됐다. 수집기만 가짜이고 대상 해석은 실제 술어다.
     *
     * <p><b>대조군</b>: 같은 닫힌 행을 옛 갈래({@link CompanyProfileJob#runForCandidates})에 주면 거절한다.
     */
    @Test
    void sectorStepResolvesClosedRowsWithTheSamePredicateAsTheRefill() throws Exception {
        jdbc.update("INSERT INTO indicator_trade_date (market_code, trade_date) VALUES ('US', ?)", WINDOW_START);
        row("RWCLOSED~2026-03-01", LocalDate.of(2026, 3, 2));
        BatchExecutions executions = mock(BatchExecutions.class);
        BatchExecutionContext context = mock(BatchExecutionContext.class);
        when(executions.start(any(), any(), any(), anyInt(), any())).thenReturn(context);
        CompanyProfileJob job = new CompanyProfileJob(mock(com.stockscreener.pipeline.collector.CompanyProfileCollector.class), registrar, executions);

        job.runForRefillWindow(LocalDate.of(2026, 10, 18), BatchExecutionRecorder.TriggeredBy.MANUAL, List.of("RWCLOSED~2026-03-01"));

        org.mockito.ArgumentCaptor<SecurityRef> target = org.mockito.ArgumentCaptor.captor();
        verify(context).process(target.capture(), any(), any());
        assertThat(target.getValue().ticker()).isEqualTo("RWCLOSED~2026-03-01");
        assertThatThrownBy(() -> job.runForCandidates(LocalDate.of(2026, 10, 18), BatchExecutionRecorder.TriggeredBy.MANUAL,
                List.of("RWCLOSED~2026-03-01")))
                .as("대조군 — 오늘 후보 술어는 닫힌 행을 안 푼다").isInstanceOf(IllegalArgumentException.class);
    }

    private void row(String ticker, LocalDate eraTo) {
        jdbc.update("INSERT INTO stock (market_code, ticker, name, era_to, is_active) VALUES ('US', ?, ?, ?, true)", ticker, ticker, eraTo);
    }
}
