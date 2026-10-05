package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.batch.BatchExecutionRecorder.Scope;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder.TriggeredBy;
import com.stockscreener.pipeline.datasource.GovernanceEndpoint;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실행마다 찍는 범위 표지(V63 · ADR-0172)를 고정한다.
 *
 * <p><b>지키는 것은 두 방향이다.</b> 좁힌 실행이 {@code FULL}로 찍히면 그 실행이 무거운 잡의 시계를 밀어 첫 정기
 * 실행이 늦는다([A56]). 정기 실행이 {@code NARROW}로 찍히면 그 SCHEDULER 행이 시계에서 빠져 같은 잡이 매 평일 다시
 * 돈다. 정기 호출이 넘기는 인자가 {@code FULL}인지는 {@code PipelineSchedulerChainTest}가 스케줄러에서 잡은 인자로 묻는다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 기록 계층이 {@code REQUIRES_NEW}로 커밋한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class BatchExecutionScopeTest {

    private static final String JOB_NAME = "scope-probe";

    /** 2026-10-06 10:00 KST. 시험이 오늘 날짜에 기대지 않게 시계를 고정한다. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY_KST = LocalDate.of(2026, 10, 6);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution WHERE job_name = ?", JOB_NAME);
    }

    @Test
    @DisplayName("범위를 안 주면 행을 안 만든다 — DB는 이력 때문에 NULL을 받고 신선도는 NULL을 전량으로 센다")
    void refusesMissingScope() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);

        assertThatThrownBy(() -> recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.MANUAL, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(rows()).isZero();
    }

    @Test
    @DisplayName("잡이 정한 범위를 그대로 찍는다 — 기준일이 지금이고 꼬리가 아니면")
    void recordsTheDeclaredScope() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);

        long full = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 1, Scope.FULL);
        long narrow = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.MANUAL, 1, Scope.NARROW);

        assertThat(scopeOf(full)).isEqualTo("FULL");
        assertThat(scopeOf(narrow)).isEqualTo("NARROW");
    }

    @Test
    @DisplayName("꼬리는 언제나 좁다 — 잡이 FULL이라 해도(정의가 「티커로 좁힌 정기 경로」다)")
    void gapFillIsAlwaysNarrow() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);

        long id = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.GAP_FILL, 1, Scope.FULL);

        assertThat(scopeOf(id)).isEqualTo("NARROW");
    }

    @Test
    @DisplayName("기준일이 지금이 아니면 좁다 — --as-of를 과거로 주면 창 끝이 그 날짜다")
    void pastOrFarFutureTargetIsNarrow() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(null, CLOCK);

        // 경계: 7일 전과 내일은 지금이다. 정기 실행의 기준일은 당일 · 직전 평일(최대 3일 전)이다.
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.minusDays(7), TriggeredBy.MANUAL, Scope.FULL))
                .isEqualTo(Scope.FULL);
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.plusDays(1), TriggeredBy.MANUAL, Scope.FULL))
                .isEqualTo(Scope.FULL);
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.minusDays(8), TriggeredBy.MANUAL, Scope.FULL))
                .isEqualTo(Scope.NARROW);
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.plusDays(2), TriggeredBy.MANUAL, Scope.FULL))
                .isEqualTo(Scope.NARROW);
        assertThat(recorder.recordedScope(JOB_NAME, null, TriggeredBy.MANUAL, Scope.FULL))
                .isEqualTo(Scope.NARROW);
    }

    @Test
    @DisplayName("한국 날짜로 잰다 — UTC 날짜로 재면 오전 9시 전 실행의 「오늘」이 하루 어긋난다")
    void todayIsTheKoreanDate() {
        // 2026-10-06 08:00 KST = 10-05 23:00 UTC. 한국 날짜로는 10-06이고 7일 전 경계는 09-29다.
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(null,
                Clock.fixed(Instant.parse("2026-10-05T23:00:00Z"), ZoneOffset.UTC));

        assertThat(recorder.recordedScope(JOB_NAME, LocalDate.of(2026, 10, 7), TriggeredBy.MANUAL, Scope.FULL))
                .as("내일(10-07)은 지금이다 — UTC 날짜(10-05)로 재면 모레라 NARROW가 된다")
                .isEqualTo(Scope.FULL);
    }

    @Test
    @DisplayName("재실행은 언제나 좁다 — 실패분만 다시 돈다")
    void retryIsNarrow() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);
        long first = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 3, Scope.FULL);

        long retry = recorder.startRetry(JOB_NAME, TODAY_KST, 1, first);

        assertThat(scopeOf(retry)).isEqualTo("NARROW");
    }

    @Test
    @DisplayName("무거운 잡 셋의 좁힌 손 실행은 NARROW다 — [A56]의 실물 모양들(티커 · 표 하나 · 분기 없음)")
    void narrowedHeavyRunsAreNarrow() {
        // 표지 전 시계를 만든 좁힌 손 실행들의 모양 — 09-20 #633(G 337종목) · 09-23 #664(FSR 129종목) · 10-03 #710(FR 1종목).
        assertThat(GovernanceLoadJob.scopeOf(null, null, List.of(), List.of("005930"))).isEqualTo(Scope.NARROW);
        assertThat(GovernanceLoadJob.scopeOf(null, null, List.of(GovernanceEndpoint.DIVIDEND), List.of()))
                .isEqualTo(Scope.NARROW);
        assertThat(GovernanceLoadJob.scopeOf(null, 2, List.of(), List.of())).isEqualTo(Scope.NARROW);
        assertThat(GovernanceLoadJob.scopeOf(2025, null, List.of(), List.of())).isEqualTo(Scope.NARROW);
        assertThat(FinancialRefillJob.scopeOf(null, List.of("AAPL"), null)).isEqualTo(Scope.NARROW);
        assertThat(FinancialRefillJob.scopeOf(null, List.of(), "US_TOP1000")).isEqualTo(Scope.NARROW);
        assertThat(FinancialRefillJob.scopeOf(FinancialRefillJob.DEFAULT_YEARS_BACK - 1, List.of(), null))
                .isEqualTo(Scope.NARROW);
        assertThat(FullStatementRefillJob.scopeOf(null, List.of(), false))
                .as("--all-quarters 없는 전 종목 실행은 FY만 받는다")
                .isEqualTo(Scope.NARROW);
        assertThat(FullStatementRefillJob.scopeOf(null, List.of("005930"), true)).isEqualTo(Scope.NARROW);
    }

    @Test
    @DisplayName("전량 손 실행은 FULL이다 — 정기 실행과 같거나 그것을 덮는 범위면 시계를 민다")
    void fullManualRunsAreFull() {
        assertThat(GovernanceLoadJob.scopeOf(null, null, null, null)).isEqualTo(Scope.FULL);
        assertThat(GovernanceLoadJob.scopeOf(null, null, List.of(GovernanceEndpoint.values()), List.of()))
                .isEqualTo(Scope.FULL);
        assertThat(FinancialRefillJob.scopeOf(FinancialRefillJob.DEFAULT_YEARS_BACK + 3, List.of(), null))
                .isEqualTo(Scope.FULL);
        assertThat(FullStatementRefillJob.scopeOf(null, null, true)).isEqualTo(Scope.FULL);
    }

    private long rows() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution WHERE job_name = ?", Long.class, JOB_NAME);
        return count == null ? 0 : count;
    }

    private String scopeOf(long id) {
        return jdbcTemplate.queryForObject("SELECT scope FROM batch_execution WHERE id = ?", String.class, id);
    }
}
