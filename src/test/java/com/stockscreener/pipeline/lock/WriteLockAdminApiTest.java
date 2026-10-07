package com.stockscreener.pipeline.lock;

import com.stockscreener.auth.AllowlistOidcUserService;
import com.stockscreener.pipeline.batch.DailyUpdateJob;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 화면이 쓰기 잠금 보유자를 보고 버튼을 잠그는 HTTP 표면(ADR-0175 · 잠금 결정 12).
 *
 * <p>진짜 {@code AdminJobLauncher}와 진짜 {@link WriteLock}이다 — 잠금 표는 잠금 저장소가 자기 연결(autocommit)로 읽으므로 이 시험은
 * 트랜잭션으로 감싸지 않고 행을 커밋한다. 보유자는 살아 있는 자식 프로세스({@code sleep})의 신원이라 사망 술어가 진짜로 「산 남」을 본다.
 * MUST: 잠금이 비었을 때 실행 POST를 보내지 않는다 — 진짜 잡이 떠 외부 API를 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@WithMockUser(authorities = {AllowlistOidcUserService.ROLE_USER, AllowlistOidcUserService.ROLE_ADMIN})
class WriteLockAdminApiTest {

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Process bootRun;
    private ProcessIdentity bootRunIdentity;

    @Autowired
    void setUp(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @BeforeEach
    void holdAsAnotherJvm() throws Exception {
        jdbcTemplate.update("DELETE FROM write_lock");
        bootRun = new ProcessBuilder("sleep", "600").start();
        bootRunIdentity = ProcessIdentity.of(bootRun.toHandle()).orElseThrow();
        jdbcTemplate.update("""
                INSERT INTO write_lock (db_name, version, holder_kind, token, purpose, holder_pid, holder_start,
                                        acquired_at, heartbeat_at, last_activity_at)
                VALUES (current_database(), 1, 'BOOTRUN', gen_random_uuid(), 'job:financial-refill', ?, ?, now(), now(), now())
                """, bootRunIdentity.pid(), bootRunIdentity.startEpochSecond());
    }

    @AfterEach
    void tearDown() {
        bootRun.destroyForcibly();
        jdbcTemplate.update("DELETE FROM write_lock");
    }

    @Test
    @DisplayName("다른 JVM이 쥐면 잡 목록이 잡마다 보유자를 싣는다 — 화면이 누르기 전에 버튼을 잠근다")
    void jobsCarryTheBlockingHolder() throws Exception {
        mockMvc.perform(get("/api/admin/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].blockedBy.kind", everyItem(is("BOOTRUN"))))
                .andExpect(jsonPath("$[0].blockedBy.pid").value(bootRunIdentity.pid()))
                .andExpect(jsonPath("$[0].blockedBy.purpose").value("job:financial-refill"))
                .andExpect(jsonPath("$[0].blockedBy.waiting").value(false));
    }

    @Test
    @DisplayName("보유자가 죽었거나 비었으면 보유자가 없다 — 획득과 같은 술어라 잠긴 버튼이 낡은 행에 매이지 않는다")
    void deadOrEmptyHolderDoesNotBlock() throws Exception {
        bootRun.destroyForcibly().waitFor();
        mockMvc.perform(get("/api/admin/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].blockedBy", everyItem(nullValue())));

        jdbcTemplate.update("DELETE FROM write_lock");
        mockMvc.perform(get("/api/admin/jobs"))
                .andExpect(jsonPath("$[*].blockedBy", everyItem(nullValue())));
    }

    @Test
    @DisplayName("그래도 누르면 409 write-lock-held와 보유자 — 같은 잡 실행 중(job-already-running)과 가른다")
    void launchWhileAnotherJvmHoldsIsConflict() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/" + DailyUpdateJob.JOB_NAME + "/run").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:stockscreener:write-lock-held"))
                .andExpect(jsonPath("$.holderKind").value("BOOTRUN"))
                .andExpect(jsonPath("$.holderPid").value(bootRunIdentity.pid()))
                .andExpect(jsonPath("$.purpose").value("job:financial-refill"));

        // 거절이 「실행 중」 표지를 남기지 않는다 — 남으면 그 잡 버튼이 영영 「실행 중」으로 잠긴다.
        mockMvc.perform(get("/api/admin/jobs"))
                .andExpect(jsonPath("$[?(@.name == '" + DailyUpdateJob.JOB_NAME + "')].running").value(false));
    }
}
