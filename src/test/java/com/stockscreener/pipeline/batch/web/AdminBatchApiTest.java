package com.stockscreener.pipeline.batch.web;

import com.stockscreener.auth.AllowlistOidcUserService;
import com.stockscreener.pipeline.batch.AdminJobLauncher;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.pipeline.batch.IndicatorBackfillJob;
import com.stockscreener.pipeline.batch.UniverseRebalanceJob;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 이력 조회와 수동 개입의 HTTP 표면 (spec §4, §9의 다섯째 수용 기준).
 *
 * <p>{@link AdminJobLauncher}를 가짜로 바꾼다. 진짜 런처는 실제 잡을 띄워 외부 API 쿼터를
 * 태우고, 여기서 확인하려는 것은 <b>결과가 어떤 상태 코드로 번역되는가</b>이지 잡이 도는지가
 * 아니다. 가드 자체는 {@code AdminJobLauncherTest}가 진짜 런처로 확인한다.
 *
 * <p>조회 쪽은 진짜 {@code BatchExecutionReader}와 실제 PostgreSQL을 쓴다 —
 * {@code DISTINCT ON}과 상관 서브쿼리는 가짜로 바꾸면 아무것도 검증되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
@WithMockUser(authorities = {AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN})
class AdminBatchApiTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 8, 5);

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AdminJobLauncher launcher;

    private long stockId;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");

        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active)
                VALUES ('KOSPI', '900201', '실패종목', true)
                ON CONFLICT (market_code, ticker) DO UPDATE SET name = EXCLUDED.name
                RETURNING id
                """, Long.class);

        when(launcher.catalog()).thenReturn(List.of(new AdminJobLauncher.Descriptor(
                DailyUpdateJob.JOB_NAME, "일일 갱신", "설명",
                AdminJobLauncher.Parameters.TARGET_DATE, 350)));
        when(launcher.runningJobs()).thenReturn(Set.of());
        when(launcher.isRunning(anyString())).thenReturn(false);
        when(launcher.isLaunchable(DailyUpdateJob.JOB_NAME)).thenReturn(true);
        when(launcher.launch(anyString(), any())).thenReturn(true);
        when(launcher.relaunchFailures(anyLong(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("실행 이력이 최근 순으로 오고 잡 이름으로 좁혀진다")
    void listsExecutions() throws Exception {
        insertExecution(DailyUpdateJob.JOB_NAME, "SUCCESS", 0);
        insertExecution(UniverseRebalanceJob.JOB_NAME, "FAILED", 0);

        mockMvc.perform(get("/api/admin/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/admin/batches").param("jobName", UniverseRebalanceJob.JOB_NAME))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("FAILED"));

        mockMvc.perform(get("/api/admin/batches").param("status", "SUCCESS"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jobName").value(DailyUpdateJob.JOB_NAME));
    }

    @Test
    @DisplayName("요약이 잡별 최근 한 건과 미해결 에러 건수를 준다")
    void summarisesLatestPerJobAndUnresolvedCount() throws Exception {
        long old = insertExecution(DailyUpdateJob.JOB_NAME, "FAILED", 0);
        long latest = insertExecution(DailyUpdateJob.JOB_NAME, "PARTIAL_SUCCESS", 0);
        insertExecution(UniverseRebalanceJob.JOB_NAME, "SUCCESS", 0);

        insertFailure(latest, "PRICE", false);
        insertFailure(old, "FINANCIAL", true);

        mockMvc.perform(get("/api/admin/batches/summary"))
                .andExpect(status().isOk())
                // 일일 갱신이 두 번 돌아도 유니버스의 마지막 결과가 밀려나지 않는다.
                .andExpect(jsonPath("$.latestByJob.length()").value(2))
                // 해결된 실패는 빠진다. 안 빠지면 요약이 영원히 줄지 않아 무의미해진다.
                .andExpect(jsonPath("$.unresolvedFailureCount").value(1))
                .andExpect(jsonPath("$.runningJobs.length()").value(0));
    }

    @Test
    @DisplayName("상세가 실패 종목을 종목명과 함께 준다 — 티커만으로는 무엇이 실패했는지 모른다")
    void detailIncludesFailuresWithStockName() throws Exception {
        long executionId = insertExecution(DailyUpdateJob.JOB_NAME, "PARTIAL_SUCCESS", 1);
        insertFailure(executionId, "PRICE", false);

        mockMvc.perform(get("/api/admin/batches/" + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.execution.id").value(executionId))
                .andExpect(jsonPath("$.execution.unresolvedFailureCount").value(1))
                .andExpect(jsonPath("$.retrySupported").value(true))
                .andExpect(jsonPath("$.failures[0].ticker").value("900201"))
                .andExpect(jsonPath("$.failures[0].stockName").value("실패종목"))
                .andExpect(jsonPath("$.failures[0].failedPhase").value("PRICE"))
                .andExpect(jsonPath("$.failures[0].resolved").value(false));
    }

    @Test
    @DisplayName("없는 실행 id는 404다")
    void unknownExecutionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/batches/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:batch-execution-not-found"));
    }

    @Test
    @DisplayName("재실행이 202이고 원래 실행의 대상 일자를 그대로 쓴다")
    void retryIsAccepted() throws Exception {
        long executionId = insertExecution(DailyUpdateJob.JOB_NAME, "PARTIAL_SUCCESS", 1);
        insertFailure(executionId, "PRICE", false);

        mockMvc.perform(post("/api/admin/batches/" + executionId + "/retry").with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value(DailyUpdateJob.JOB_NAME));

        // 재실행해도 대상 일자는 바뀌지 않는다. 실행 시각으로 돌리면 어제 것을 오늘 것으로 덮는다.
        verify(launcher).relaunchFailures(executionId, TARGET);
    }

    @Test
    @DisplayName("미해결 실패가 없으면 재실행이 409다 — 대상 0건짜리 실행 행만 남는다")
    void retryWithoutUnresolvedFailuresIsConflict() throws Exception {
        long executionId = insertExecution(DailyUpdateJob.JOB_NAME, "SUCCESS", 0);

        mockMvc.perform(post("/api/admin/batches/" + executionId + "/retry").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:conflict"));
    }

    @Test
    @DisplayName("실패 단위가 종목이 아닌 잡은 재실행이 409다 — 조용히 아무것도 안 하면 결과를 기다린다")
    void retryUnsupportedJobIsConflict() throws Exception {
        long executionId = insertExecution(IndicatorBackfillJob.JOB_NAME, "PARTIAL_SUCCESS", 1);
        insertFailure(executionId, "INDICATOR", false);

        mockMvc.perform(post("/api/admin/batches/" + executionId + "/retry").with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("이미 도는 중이면 409이고 프론트가 분기할 수 있는 type을 준다")
    void launchWhileRunningIsConflict() throws Exception {
        when(launcher.launch(anyString(), any())).thenReturn(false);

        mockMvc.perform(post("/api/admin/jobs/" + DailyUpdateJob.JOB_NAME + "/run").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:job-already-running"));
    }

    @Test
    @DisplayName("잡 기동은 202다 — 850종목 수집이 HTTP 응답을 기다리지 않는다")
    void launchIsAccepted() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/" + DailyUpdateJob.JOB_NAME + "/run").with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value(DailyUpdateJob.JOB_NAME));
    }

    @Test
    @DisplayName("IMPORTANT — 목록 밖 잡은 404다. 초기 적재가 목록 밖인 것이 의도다")
    void unknownJobIsNotFound() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/initial-load/run").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:job-not-found"));
    }

    @Test
    @DisplayName("잡 목록을 서버가 준다 — 프론트가 이름을 하드코딩하지 않는다")
    void listsLaunchableJobs() throws Exception {
        mockMvc.perform(get("/api/admin/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value(DailyUpdateJob.JOB_NAME))
                .andExpect(jsonPath("$[0].parameters").value("TARGET_DATE"))
                // 누르는 순간 쿼터가 나간다. 그 사실이 버튼 옆에 있어야 한다.
                .andExpect(jsonPath("$[0].externalCallEstimate").value(350))
                .andExpect(jsonPath("$[0].running").value(false));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private long insertExecution(String jobName, String status, int failureCount) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO batch_execution
                    (job_name, target_date, status, triggered_by, target_count, failure_count)
                VALUES (?, ?, ?, 'SCHEDULER', 10, ?)
                RETURNING id
                """, Long.class, jobName, TARGET, status, failureCount);
    }

    private void insertFailure(long executionId, String phase, boolean resolved) {
        jdbcTemplate.update("""
                INSERT INTO batch_execution_failure
                    (execution_id, stock_id, failed_phase, source_name, failure_kind, message, resolved)
                VALUES (?, ?, ?, 'data-go-kr', 'TRANSIENT', '일시적 실패', ?)
                """, executionId, stockId, phase, resolved);
    }
}
