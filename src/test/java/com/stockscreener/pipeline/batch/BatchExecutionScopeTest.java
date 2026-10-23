package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.batch.BatchExecutionRecorder.DeclaredScope;
import com.stockscreener.pipeline.batch.BatchExecutionRecorder.ScopeReason;
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

    private static final DeclaredScope ARGS = DeclaredScope.narrow(ScopeReason.ARGS);
    private static final DeclaredScope PAST_DATE = DeclaredScope.narrow(ScopeReason.PAST_DATE);

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

        long full = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 1, DeclaredScope.FULL);
        long narrow = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.MANUAL, 1, ARGS);

        assertThat(scopeOf(full)).isEqualTo("FULL");
        assertThat(scopeOf(narrow)).isEqualTo("NARROW");
    }

    @Test
    @DisplayName("꼬리는 언제나 좁다 — 잡이 FULL이라 해도(정의가 「티커로 좁힌 정기 경로」다)")
    void gapFillIsAlwaysNarrow() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);

        long id = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.GAP_FILL, 1, DeclaredScope.FULL);

        assertThat(scopeOf(id)).isEqualTo("NARROW");
    }

    @Test
    @DisplayName("기준일이 지금이 아니면 좁다 — --as-of를 과거로 주면 창 끝이 그 날짜다")
    void pastOrFarFutureTargetIsNarrow() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(null, CLOCK);

        // 경계: 7일 전과 내일은 지금이다. 정기 실행의 기준일은 당일 · 직전 평일(최대 3일 전)이다.
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.minusDays(7), TriggeredBy.MANUAL, DeclaredScope.FULL))
                .isEqualTo(DeclaredScope.FULL);
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.plusDays(1), TriggeredBy.MANUAL, DeclaredScope.FULL))
                .isEqualTo(DeclaredScope.FULL);
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.minusDays(8), TriggeredBy.MANUAL, DeclaredScope.FULL))
                .isEqualTo(PAST_DATE);
        assertThat(recorder.recordedScope(JOB_NAME, TODAY_KST.plusDays(2), TriggeredBy.MANUAL, DeclaredScope.FULL))
                .isEqualTo(PAST_DATE);
        assertThat(recorder.recordedScope(JOB_NAME, null, TriggeredBy.MANUAL, DeclaredScope.FULL))
                .isEqualTo(PAST_DATE);
    }

    @Test
    @DisplayName("한국 날짜로 잰다 — UTC 날짜로 재면 오전 9시 전 실행의 「오늘」이 하루 어긋난다")
    void todayIsTheKoreanDate() {
        // 2026-10-06 08:00 KST = 10-05 23:00 UTC. 한국 날짜로는 10-06이고 7일 전 경계는 09-29다.
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(null,
                Clock.fixed(Instant.parse("2026-10-05T23:00:00Z"), ZoneOffset.UTC));

        assertThat(recorder.recordedScope(JOB_NAME, LocalDate.of(2026, 10, 7), TriggeredBy.MANUAL, DeclaredScope.FULL))
                .as("내일(10-07)은 지금이다 — UTC 날짜(10-05)로 재면 모레라 NARROW가 된다")
                .isEqualTo(DeclaredScope.FULL);
    }

    @Test
    @DisplayName("재실행은 언제나 좁다 — 실패분만 다시 돈다")
    void retryIsNarrow() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);
        long first = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 3, DeclaredScope.FULL);

        long retry = recorder.startRetry(JOB_NAME, TODAY_KST, 1, first);

        assertThat(scopeOf(retry)).isEqualTo("NARROW");
    }

    @Test
    @DisplayName("무거운 잡 셋의 좁힌 손 실행은 NARROW다 — [A56]의 실물 모양들(티커 · 표 하나 · 분기 없음)")
    void narrowedHeavyRunsAreNarrow() {
        // 표지 전 시계를 만든 좁힌 손 실행들의 모양 — 09-20 #633(G 337종목) · 09-23 #664(FSR 129종목) · 10-03 #710(FR 1종목).
        assertThat(GovernanceLoadJob.scopeOf(null, null, List.of(), List.of("005930"))).isEqualTo(ARGS);
        assertThat(GovernanceLoadJob.scopeOf(null, null, List.of(GovernanceEndpoint.DIVIDEND), List.of()))
                .isEqualTo(ARGS);
        assertThat(GovernanceLoadJob.scopeOf(null, 2, List.of(), List.of())).isEqualTo(ARGS);
        assertThat(GovernanceLoadJob.scopeOf(2025, null, List.of(), List.of())).isEqualTo(ARGS);
        assertThat(FinancialRefillJob.scopeOf(null, List.of("AAPL"), null)).isEqualTo(ARGS);
        assertThat(FinancialRefillJob.scopeOf(null, List.of(), "US_TOP1000")).isEqualTo(ARGS);
        assertThat(FinancialRefillJob.scopeOf(FinancialRefillJob.DEFAULT_YEARS_BACK - 1, List.of(), null))
                .isEqualTo(ARGS);
        assertThat(FullStatementRefillJob.scopeOf(null, List.of(), false))
                .as("--all-quarters 없는 전 종목 실행은 FY만 받는다")
                .isEqualTo(ARGS);
        assertThat(FullStatementRefillJob.scopeOf(null, List.of("005930"), true)).isEqualTo(ARGS);
    }

    @Test
    @DisplayName("전량 손 실행은 FULL이다 — 정기 실행과 같거나 그것을 덮는 범위면 시계를 민다")
    void fullManualRunsAreFull() {
        assertThat(GovernanceLoadJob.scopeOf(null, null, null, null)).isEqualTo(DeclaredScope.FULL);
        assertThat(GovernanceLoadJob.scopeOf(null, null, List.of(GovernanceEndpoint.values()), List.of()))
                .isEqualTo(DeclaredScope.FULL);
        assertThat(FinancialRefillJob.scopeOf(FinancialRefillJob.DEFAULT_YEARS_BACK + 3, List.of(), null))
                .isEqualTo(DeclaredScope.FULL);
        assertThat(FullStatementRefillJob.scopeOf(null, null, true)).isEqualTo(DeclaredScope.FULL);
    }

    @Test
    @DisplayName("NARROW에는 사유가 함께 찍힌다 — 잡의 선언(ARGS) · 꼬리(GAP_FILL) · 기준일(PAST_DATE) · FULL은 사유 없음(V65)")
    void recordsTheReason() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);

        long full = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 1, DeclaredScope.FULL);
        long args = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.MANUAL, 1, ARGS);
        long judge = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.ADMIN, 1,
                DeclaredScope.narrow(ScopeReason.NO_SCHEDULED_RANGE));
        long tail = recorder.start(JOB_NAME, TODAY_KST.minusDays(30), TriggeredBy.GAP_FILL, 1, ARGS);
        long past = recorder.start(JOB_NAME, TODAY_KST.minusDays(30), TriggeredBy.MANUAL, 1, ARGS);

        assertThat(reasonOf(full)).isNull();
        assertThat(reasonOf(args)).isEqualTo("ARGS");
        assertThat(reasonOf(judge)).isEqualTo("NO_SCHEDULED_RANGE");
        assertThat(reasonOf(tail)).as("우선순위 GAP_FILL > PAST_DATE > 잡 선언").isEqualTo("GAP_FILL");
        assertThat(reasonOf(past)).as("기준일이 잡의 인자 사유보다 먼저다").isEqualTo("PAST_DATE");
    }

    @Test
    @DisplayName("재실행의 사유는 RERUN이고, 기준일이 지금이 아니면 PAST_DATE다 — startRetry는 recordedScope를 안 지난다")
    void retryReasonFollowsTheSamePriority() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);
        long first = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 3, DeclaredScope.FULL);

        long current = recorder.startRetry(JOB_NAME, TODAY_KST, 1, first);
        long past = recorder.startRetry(JOB_NAME, TODAY_KST.minusDays(30), 1, first);

        assertThat(reasonOf(current)).isEqualTo("RERUN");
        assertThat(reasonOf(past)).isEqualTo("PAST_DATE");
    }

    @Test
    @DisplayName("FULL로 시작한 실행이 시장 미완료로 끝나면 같은 마감에서 NARROW·MARKET_INCOMPLETE — 상태·계수는 그대로(ADR-0174)")
    void incompleteFullRunIsDemotedAtFinish() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);
        long id = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 3, DeclaredScope.FULL);

        BatchStatus status = recorder.finish(id, new BatchProgress(2, 1, 10, null, List.of()), "앞머리",
                List.of("KOSPI", "US"));

        assertThat(status).isEqualTo(BatchStatus.PARTIAL_SUCCESS);
        assertThat(scopeOf(id)).isEqualTo("NARROW");
        assertThat(reasonOf(id)).isEqualTo("MARKET_INCOMPLETE");
        assertThat(marketsOf(id)).isEqualTo("KOSPI,US");
        assertThat(jdbcTemplate.queryForObject("SELECT note FROM batch_execution WHERE id = ?", String.class, id))
                .isEqualTo("앞머리");
    }

    @Test
    @DisplayName("시작부터 NARROW면 마감이 사유를 안 바꾸고 미완료 시장만 적는다 · 미완료가 없으면 FULL 그대로")
    void narrowRunKeepsItsReasonAndCompleteRunStaysFull() {
        BatchExecutionRecorder recorder = new BatchExecutionRecorder(jdbcTemplate, CLOCK);
        long narrow = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.MANUAL, 1, ARGS);
        long complete = recorder.start(JOB_NAME, TODAY_KST, TriggeredBy.SCHEDULER, 1, DeclaredScope.FULL);

        recorder.finish(narrow, new BatchProgress(0, 1, 0, null, List.of()), null, List.of("US"));
        recorder.finish(complete, new BatchProgress(1, 0, 0, null, List.of()), null, List.of());

        assertThat(reasonOf(narrow)).isEqualTo("ARGS");
        assertThat(marketsOf(narrow)).isEqualTo("US");
        assertThat(scopeOf(complete)).isEqualTo("FULL");
        assertThat(reasonOf(complete)).isNull();
        assertThat(marketsOf(complete)).isNull();
    }

    @Test
    @DisplayName("DB가 「FULL인데 사유·미완료 시장이 있다」를 거부한다 — 시계에 세는 행에 안 세는 사유가 붙는 모순(V65 CHECK)")
    void databaseRefusesReasonOnFullRows() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, target_count, scope,
                    scope_reason, started_at)
                VALUES (?, ?, 'RUNNING', 'MANUAL', 0, 'FULL', 'ARGS', now())
                """, JOB_NAME, TODAY_KST)).hasMessageContaining("ck_batch_scope_reason_narrow");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, target_count, scope,
                    incomplete_markets, started_at)
                VALUES (?, ?, 'RUNNING', 'MANUAL', 0, 'FULL', '{US}', now())
                """, JOB_NAME, TODAY_KST)).hasMessageContaining("ck_batch_incomplete_markets_narrow");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO batch_execution (job_name, target_date, status, triggered_by, target_count, scope,
                    scope_reason, started_at)
                VALUES (?, ?, 'RUNNING', 'MANUAL', 0, 'NARROW', 'GUESS', now())
                """, JOB_NAME, TODAY_KST)).hasMessageContaining("ck_batch_scope_reason");
    }

    @Test
    @DisplayName("선언은 FULL이면 사유가 없고 NARROW면 있다 · MARKET_INCOMPLETE는 시작 때 선언하지 못한다")
    void declaredScopeInvariants() {
        assertThatThrownBy(() -> new DeclaredScope(BatchExecutionRecorder.Scope.NARROW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeclaredScope(BatchExecutionRecorder.Scope.FULL, ScopeReason.ARGS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeclaredScope.narrow(ScopeReason.MARKET_INCOMPLETE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DeclaredScope.byArgs(true)).isEqualTo(DeclaredScope.FULL);
        assertThat(DeclaredScope.byArgs(false)).isEqualTo(ARGS);
    }

    private String reasonOf(long id) {
        return jdbcTemplate.queryForObject("SELECT scope_reason FROM batch_execution WHERE id = ?", String.class, id);
    }

    private String marketsOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT array_to_string(incomplete_markets, ',') FROM batch_execution WHERE id = ?", String.class, id);
    }

    private long rows() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution WHERE job_name = ?", Long.class, JOB_NAME);
        return count == null ? 0 : count;
    }

    private String scopeOf(long id) {
        return jdbcTemplate.queryForObject("SELECT scope FROM batch_execution WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("켜진 관문을 실행 행에 남긴다 — 다 꺼졌으면 NULL이고 그때 SQL은 이 열이 생기기 전과 같다")
    void recordsTheGateState() {
        BatchExecutionRecorder usOnly = new BatchExecutionRecorder(jdbcTemplate, CLOCK, BatchExecutionRecorder.gateState(true, false));
        long id = usOnly.start(JOB_NAME, TODAY_KST, TriggeredBy.MANUAL, 1, DeclaredScope.FULL);
        assertThat(jdbcTemplate.queryForObject("SELECT gate_state FROM batch_execution WHERE id = ?", String.class, id))
                .isEqualTo("us");

        long off = new BatchExecutionRecorder(jdbcTemplate, CLOCK)
                .start(JOB_NAME, TODAY_KST, TriggeredBy.MANUAL, 1, DeclaredScope.FULL);
        assertThat(jdbcTemplate.queryForObject("SELECT gate_state FROM batch_execution WHERE id = ?", String.class, off))
                .isNull();
        assertThat(BatchExecutionRecorder.gateState(true, true)).isEqualTo("us,kr");
        assertThat(BatchExecutionRecorder.gateState(false, true)).isEqualTo("kr");
    }
}
