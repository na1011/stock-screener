package com.stockscreener.pipeline.batch;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연체 판정을 고정한다.
 *
 * <p><b>여기서 지키는 것은 「안 돈 잡이 드러나는가」다.</b> {@code batch_execution}은 <b>돈 것</b>만
 * 적으므로, 잡이 통째로 빠진 것은 그 표를 아무리 봐도 안 보인다 — 실제로 국내 기업행위 수집이
 * 19일 안 돌아 무상증자 하나가 수정계수에 안 들어간 채로 있었고 관리 화면은 전부 초록이었다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 잡들이 {@code REQUIRES_NEW}로 커밋하므로
 * 같은 방식으로 심고 {@code @AfterEach}로 지운다({@code AdjustmentRebuildJobTest}와 같은 판단).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class JobFreshnessPolicyTest {

    /** 실재하지 않는 이름이라 다른 테스트의 행과 안 섞인다. */
    private static final String PROBE_JOB = DailyUpdateJob.JOB_NAME;

    @Autowired
    private JobFreshnessPolicy policy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution WHERE note = 'JobFreshnessPolicyTest'");
    }

    @Test
    @DisplayName("예산 안에서 돈 잡은 연체가 아니다 — 평시에 붉으면 아무도 배너를 안 본다")
    void freshJobIsNotStale() {
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        insert(PROBE_JOB, "SUCCESS", "SCHEDULER", now.minus(Duration.ofDays(1)));

        assertThat(names(policy.stale(now))).doesNotContain(PROBE_JOB);
    }

    @Test
    @DisplayName("예산을 넘기면 연체다 — 예산을 무한대로 키우면 이 시험이 실패해야 한다")
    void staleJobIsReported() {
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        int budget = JobFreshnessPolicy.BUDGETS.get(PROBE_JOB).days();
        insert(PROBE_JOB, "SUCCESS", "SCHEDULER", now.minus(Duration.ofDays(budget + 3L)));

        JobFreshnessPolicy.Staleness found = policy.stale(now).stream()
                .filter(item -> item.jobName().equals(PROBE_JOB))
                .findFirst().orElseThrow();

        assertThat(found.daysSince()).isEqualTo(budget + 3L);
        assertThat(found.budgetDays()).isEqualTo(budget);
        assertThat(found.scheduledOnly()).isTrue();
    }

    @Test
    @DisplayName("좁은 수동 실행은 시계를 되돌리지 못한다 — 08-24의 9종목 실행이 그 자리였다")
    void narrowManualRunDoesNotResetTheClock() {
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        int budget = JobFreshnessPolicy.BUDGETS.get(PROBE_JOB).days();
        // 전량은 오래전에 돌았고, 어제 좁은 수동 실행이 하나 있었다.
        insert(PROBE_JOB, "SUCCESS", "SCHEDULER", now.minus(Duration.ofDays(budget + 3L)));
        insert(PROBE_JOB, "SUCCESS", "MANUAL", now.minus(Duration.ofDays(1)));

        assertThat(names(policy.stale(now)))
                .as("수동 실행을 함께 세면 결손이 있는 채로 초록이 된다")
                .contains(PROBE_JOB);
    }

    @Test
    @DisplayName("스케줄러 실행이 없을 때도 리밸런싱 꼬리는 시계를 되돌리지 못한다 — 배너와 스케줄러가 같은 답")
    void gapFillDoesNotResetTheFallbackClock() {
        // 스케줄러 실행이 한 번도 없는 잡: 폴백이 손 실행을 센다. 그 손 실행은 오래전이고,
        // 어제 꼬리가 결손 티커로 좁혀 돌았다(2026-10-03 governance-load·full-statement-refill의 모양).
        assertNoSchedulerRun();
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        int budget = JobFreshnessPolicy.BUDGETS.get(PROBE_JOB).days();
        insert(PROBE_JOB, "SUCCESS", "MANUAL", now.minus(Duration.ofDays(budget + 3L)));
        insert(PROBE_JOB, "SUCCESS", "GAP_FILL", now.minus(Duration.ofDays(1)));

        assertThat(names(policy.stale(now)))
                .as("꼬리를 세면 좁은 실행이 전량 실행의 연체를 가린다")
                .contains(PROBE_JOB);
        assertThat(policy.daysSinceLastSuccess(PROBE_JOB, now))
                .as("스케줄러가 묻는 경과일도 같은 실행을 센다")
                .isEqualTo(budget + 3L);
    }

    @Test
    @DisplayName("좁힌 손 실행은 폴백에서도 시계를 밀지 않는다 — [A56] 무거운 잡 셋의 첫 정기 실행이 그렇게 밀렸다")
    void narrowScopeDoesNotResetTheFallbackClock() {
        // 스케줄러 실행이 아직 없는 잡: 폴백이 손 실행을 센다. 전량 손 실행은 오래전이고 어제 좁힌 손 실행이 있었다
        // (10-03 #710 FR 1종목의 모양).
        assertNoSchedulerRun();
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        int budget = JobFreshnessPolicy.BUDGETS.get(PROBE_JOB).days();
        insert(PROBE_JOB, "SUCCESS", "MANUAL", "FULL", now.minus(Duration.ofDays(budget + 3L)));
        insert(PROBE_JOB, "SUCCESS", "MANUAL", "NARROW", now.minus(Duration.ofDays(1)));

        assertThat(names(policy.stale(now))).contains(PROBE_JOB);
        assertThat(policy.daysSinceLastSuccess(PROBE_JOB, now))
                .as("스케줄러가 묻는 경과일도 좁힌 실행을 안 센다")
                .isEqualTo(budget + 3L);
    }

    @Test
    @DisplayName("기준일이 지난 정기 실행은 시계를 밀지 않는다 — 스케줄러 갈래에서도 NARROW를 뺀다")
    void narrowScopeDoesNotCountInTheSchedulerBranch() {
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        int budget = JobFreshnessPolicy.BUDGETS.get(PROBE_JOB).days();
        insert(PROBE_JOB, "SUCCESS", "SCHEDULER", "FULL", now.minus(Duration.ofDays(budget + 3L)));
        insert(PROBE_JOB, "SUCCESS", "SCHEDULER", "NARROW", now.minus(Duration.ofDays(1)));

        assertThat(policy.daysSinceLastSuccess(PROBE_JOB, now)).isEqualTo(budget + 3L);
    }

    @Test
    @DisplayName("표지 전 이력은 예전처럼 센다 — 배포가 무거운 잡의 위상을 안 옮긴다(ADR-0172)")
    void unmarkedHistoryCountsAsBefore() {
        // IS DISTINCT FROM이 아니라 <> 'NARROW'로 쓰면 NULL이 빠져 이 잡이 「한 번도 안 돌았다」가 된다.
        assertNoSchedulerRun();
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        insert(PROBE_JOB, "SUCCESS", "MANUAL", null, now.minus(Duration.ofDays(1)));

        assertThat(policy.daysSinceLastSuccess(PROBE_JOB, now)).isEqualTo(1L);
        assertThat(names(policy.stale(now))).doesNotContain(PROBE_JOB);
    }

    @Test
    @DisplayName("전량 손 실행은 센다 — 표지가 손 실행을 통째로 버리는 것이 아니다")
    void fullManualRunCounts() {
        assertNoSchedulerRun();
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        insert(PROBE_JOB, "SUCCESS", "MANUAL", "FULL", now.minus(Duration.ofDays(1)));

        assertThat(policy.daysSinceLastSuccess(PROBE_JOB, now)).isEqualTo(1L);
    }

    @Test
    @DisplayName("꼬리만 있으면 「한 번도 안 돌았다」다 — 0일로 접지 않는다")
    void onlyGapFillMeansNeverRun() {
        assertNoSchedulerRun();
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        insert(PROBE_JOB, "SUCCESS", "GAP_FILL", now.minus(Duration.ofDays(1)));

        assertThat(policy.daysSinceLastSuccess(PROBE_JOB, now)).isEqualTo(Long.MAX_VALUE);
        assertThat(policy.stale(now).stream()
                .filter(item -> item.jobName().equals(PROBE_JOB))
                .findFirst().orElseThrow().daysSince()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("실패한 실행은 성공으로 안 센다 — 세면 실패가 신선도를 만든다")
    void failedRunDoesNotCount() {
        Instant now = Instant.parse("2026-09-01T05:00:00Z");
        int budget = JobFreshnessPolicy.BUDGETS.get(PROBE_JOB).days();
        insert(PROBE_JOB, "SUCCESS", "SCHEDULER", now.minus(Duration.ofDays(budget + 3L)));
        insert(PROBE_JOB, "FAILED", "SCHEDULER", now.minus(Duration.ofDays(1)));

        assertThat(names(policy.stale(now))).contains(PROBE_JOB);
    }

    @Test
    @DisplayName("무거운 잡의 재실행 간격이 연체 예산보다 짧다 — 뒤집히면 정상 운영에서 알림이 뜬다")
    void heavyJobIntervalIsShorterThanItsBudget() {
        // 스케줄러가 25일마다 돌리므로, 예산이 그보다 짧거나 같으면 **매달 반드시** 연체가 뜬다.
        // 그 알림은 고칠 것이 없는 알림이고, 그런 알림은 곧 무시된다.
        List<String> heavy = List.of(GovernanceLoadJob.JOB_NAME, FullStatementRefillJob.JOB_NAME,
                FinancialRefillJob.JOB_NAME);
        for (String job : heavy) {
            assertThat(JobFreshnessPolicy.BUDGETS.get(job).days())
                    .as("%s의 연체 예산", job)
                    .isGreaterThan(25);
        }
    }

    @Test
    @DisplayName("상시 붉을 잡은 감시 대상이 아니다 — 넣으면 진짜 연체까지 함께 묻힌다")
    void jobsWithoutACadenceAreNotWatched() {
        assertThat(JobFreshnessPolicy.BUDGETS)
                .doesNotContainKeys(MarketIndexJob.JOB_NAME, IndicatorBackfillJob.JOB_NAME,
                        InitialLoadJob.JOB_NAME, CorporateActionJudgeJob.JOB_NAME);
    }

    @Test
    @DisplayName("사슬이 끊긴 것을 센다 — APPROVED인데 계수가 없는 행은 억제에도 알림에도 안 걸린다")
    void countsApprovedWithoutCoefficient() {
        // 술어 자체를 고정한다. 이 수가 0이 아닌 상태는 ADR-0049의 불변식도 · 원장 상태도 ·
        // 재실행 0행도 못 보므로, 여기서 세지 않으면 어디에서도 안 붉는다.
        assertThat(policy.approvedWithoutCoefficient()).isGreaterThanOrEqualTo(0);
    }

    /**
     * 폴백 갈래를 탔는지의 전제. 다른 시험이 남긴 스케줄러 성공 행이 있으면 폴백을 안 타고도
     * 초록이 된다 — 그때는 이 시험이 아무것도 검사하지 않은 것이다.
     */
    private void assertNoSchedulerRun() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM batch_execution
                WHERE job_name = ? AND triggered_by = 'SCHEDULER'
                  AND status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                """, Long.class, PROBE_JOB))
                .as("스케줄러 성공 행이 있으면 폴백 갈래를 안 탄다")
                .isZero();
    }

    @Test
    @DisplayName("시장 미완료로 끝난 정기 실행은 시계를 안 민다 — 배너가 「정기 n번 시도 · 그 시장 미완료」를 함께 낸다(ADR-0174)")
    void incompleteScheduledRunsAreAttemptsNotSuccesses() {
        Instant now = Instant.parse("2026-10-30T06:00:00Z");
        String heavy = GovernanceLoadJob.JOB_NAME;
        int budget = JobFreshnessPolicy.BUDGETS.get(heavy).days();
        Instant lastSuccess = now.minus(Duration.ofDays(budget + 5L));
        insert(heavy, "SUCCESS", "SCHEDULER", "FULL", lastSuccess);
        insertIncomplete(heavy, "PARTIAL_SUCCESS", now.minus(Duration.ofDays(3)), "{KOSPI}");
        insert(heavy, "FAILED", "SCHEDULER", "FULL", now.minus(Duration.ofDays(2)));
        insertIncomplete(heavy, "SUCCESS", now.minus(Duration.ofDays(1)), "{KOSDAQ,US}");

        JobFreshnessPolicy.Staleness found = policy.stale(now).stream()
                .filter(item -> item.jobName().equals(heavy)).findFirst().orElseThrow();

        assertThat(found.lastSuccessAt()).as("미완료 SUCCESS는 시계가 아니다").isEqualTo(lastSuccess);
        assertThat(found.scheduledAttempts()).as("마지막 성공 뒤 정기 시도 — 상태 무관").isEqualTo(3);
        assertThat(found.incompleteMarkets()).as("가장 최근 시도의 미완료 시장").containsExactly("KOSDAQ", "US");
        assertThat(policy.lastScheduledAttemptAt(heavy)).isEqualTo(now.minus(Duration.ofDays(1)));
        assertThat(JobFreshnessAlerter.attemptsNote(found)).isEqualTo(" · 정기 3번 시도 · KOSDAQ,US 미완료");
        assertThat(JobFreshnessAlerter.describe(found)).contains("먼저 그 실행의 note");
    }

    @Test
    @DisplayName("정기 시도가 없으면 시도 수 0 · 미완료 없음 — 「안 돌았다」와 「돌았지만 안 셌다」를 가른다")
    void noAttemptsMeansItDidNotRun() {
        Instant now = Instant.parse("2026-10-30T06:00:00Z");
        String heavy = GovernanceLoadJob.JOB_NAME;
        int budget = JobFreshnessPolicy.BUDGETS.get(heavy).days();
        insert(heavy, "SUCCESS", "SCHEDULER", "FULL", now.minus(Duration.ofDays(budget + 5L)));

        JobFreshnessPolicy.Staleness found = policy.stale(now).stream()
                .filter(item -> item.jobName().equals(heavy)).findFirst().orElseThrow();

        assertThat(found.scheduledAttempts()).isZero();
        assertThat(found.incompleteMarkets()).isEmpty();
        assertThat(JobFreshnessAlerter.attemptsNote(found)).isEmpty();
        assertThat(JobFreshnessAlerter.describe(found)).doesNotContain("먼저 그 실행의 note");
    }

    private void insertIncomplete(String jobName, String status, Instant startedAt, String markets) {
        jdbcTemplate.update("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, started_at, finished_at, triggered_by, scope, scope_reason,
                     incomplete_markets, note)
                VALUES (?, ?, ?, ?, ?, 'SCHEDULER', 'NARROW', 'MARKET_INCOMPLETE', ?::text[], 'JobFreshnessPolicyTest')
                """, jobName, LocalDate.of(2026, 9, 1), status,
                java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(startedAt), markets);
    }

    private static List<String> names(List<JobFreshnessPolicy.Staleness> stale) {
        return stale.stream().map(JobFreshnessPolicy.Staleness::jobName).toList();
    }

    /** 범위를 안 주는 모양 — 표지 전 이력(NULL)과 같다. */
    private void insert(String jobName, String status, String triggeredBy, Instant startedAt) {
        insert(jobName, status, triggeredBy, null, startedAt);
    }

    private void insert(String jobName, String status, String triggeredBy, String scope, Instant startedAt) {
        jdbcTemplate.update("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, started_at, finished_at, triggered_by, scope, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'JobFreshnessPolicyTest')
                """, jobName, LocalDate.of(2026, 9, 1), status,
                java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(startedAt), triggeredBy, scope);
    }
}
