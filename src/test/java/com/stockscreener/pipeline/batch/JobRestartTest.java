package com.stockscreener.pipeline.batch;

import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch 도입의 <b>실제 근거</b>를 검증한다.
 *
 * <p>초기 적재는 850종목 × 2년 시세를 일일 쿼터가 있는 소스에서 받으므로 한 번에 끝나지
 * 않는 것이 정상 시나리오다. 쿼터 소진으로 중단된 뒤 다음 실행이 <b>처리한 것을 건너뛰고
 * 이어서</b> 돌아야 한다. 이 성질이 없다면 Spring Batch를 쓸 이유가 대부분 사라지므로,
 * 도입 근거를 실증하는 테스트로 남긴다(ADR-0005의 검증 항목).
 *
 * <p>{@code @Transactional}을 쓰지 않는다 — JobRepository는 커밋된 트랜잭션으로 동작한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, JobRestartTest.RestartJobConfig.class})
class JobRestartTest {

    static final String JOB_NAME = "restart-probe-job";

    /** 중단 시점을 테스트가 조작하기 위한 공유 상태. */
    static final List<Integer> processed = new ArrayList<>();
    static volatile boolean failOnThirdChunk = true;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("restartProbeJob")
    private Job restartProbeJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 이전 실행이 남긴 잡 인스턴스를 지우고 시작한다.
     *
     * <p>{@code @AfterEach}만으로는 부족하다. 앞선 실행이 비정상 종료해 정리가 돌지 못하면
     * 완료된 인스턴스가 남고, 같은 파라미터의 다음 실행이
     * {@code JobInstanceAlreadyCompleteException}으로 실패한다. 그 실패는 재시작성의 결함처럼
     * 보이지만 실제로는 테스트 격리의 문제이며, 둘을 구분하지 못하면 멀쩡한 코드를 고치게 된다.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearPreviousRuns() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        processed.clear();
        failOnThirdChunk = true;
        // 잡 메타데이터를 지운다. 다음 테스트가 같은 파라미터로 새 인스턴스를 만들 수 있어야 한다.
        jdbcTemplate.update("""
                DELETE FROM batch_step_execution_context WHERE step_execution_id IN (
                    SELECT step_execution_id FROM batch_step_execution WHERE job_execution_id IN (
                        SELECT job_execution_id FROM batch_job_execution WHERE job_instance_id IN (
                            SELECT job_instance_id FROM batch_job_instance WHERE job_name = ?)))
                """, JOB_NAME);
        jdbcTemplate.update("""
                DELETE FROM batch_step_execution WHERE job_execution_id IN (
                    SELECT job_execution_id FROM batch_job_execution WHERE job_instance_id IN (
                        SELECT job_instance_id FROM batch_job_instance WHERE job_name = ?))
                """, JOB_NAME);
        jdbcTemplate.update("""
                DELETE FROM batch_job_execution_context WHERE job_execution_id IN (
                    SELECT job_execution_id FROM batch_job_execution WHERE job_instance_id IN (
                        SELECT job_instance_id FROM batch_job_instance WHERE job_name = ?))
                """, JOB_NAME);
        jdbcTemplate.update("""
                DELETE FROM batch_job_execution_params WHERE job_execution_id IN (
                    SELECT job_execution_id FROM batch_job_execution WHERE job_instance_id IN (
                        SELECT job_instance_id FROM batch_job_instance WHERE job_name = ?))
                """, JOB_NAME);
        jdbcTemplate.update("""
                DELETE FROM batch_job_execution WHERE job_instance_id IN (
                    SELECT job_instance_id FROM batch_job_instance WHERE job_name = ?)
                """, JOB_NAME);
        jdbcTemplate.update("DELETE FROM batch_job_instance WHERE job_name = ?", JOB_NAME);
    }

    @Test
    @DisplayName("중단된 잡을 재실행하면 처리한 것을 건너뛰고 이어서 돈다")
    void resumesFromWhereItStopped() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString("targetDate", "2026-08-05")
                .toJobParameters();

        // 1차 실행 — 세 번째 청크에서 중단된다(쿼터 소진을 흉내낸다).
        var firstRun = jobOperator.start(restartProbeJob, parameters);
        assertThat(firstRun.getStatus().isUnsuccessful()).isTrue();
        int processedBeforeFailure = processed.size();
        assertThat(processedBeforeFailure)
                .as("중단 전에 일부는 처리되어 있어야 한다")
                .isGreaterThan(0)
                .isLessThan(10);

        // 2차 실행 — 같은 파라미터. 재시작이므로 새 JobInstance가 아니다.
        processed.clear();
        failOnThirdChunk = false;
        var secondRun = jobOperator.start(restartProbeJob, parameters);

        assertThat(secondRun.getStatus().toString()).isEqualTo("COMPLETED");
        // 이것이 재시작성의 증거다. 처음부터 다시 돌았다면 10건이 모두 다시 처리된다.
        assertThat(processed.size())
                .as("이미 처리한 항목을 다시 처리하면 안 된다 — 쿼터가 있는 소스에서 그대로 낭비다")
                .isEqualTo(10 - processedBeforeFailure);
    }

    /**
     * 재시작을 검증하기 위한 최소 잡. 실제 수집 잡은 7단계에서 만든다.
     *
     * <p>{@code ItemReader}가 {@code ExecutionContext}에 위치를 저장하는 것이 재시작의 핵심이다.
     * 여기서는 그것을 흉내내기 위해 읽은 개수를 직접 저장·복원한다.
     */
    @TestConfiguration
    static class RestartJobConfig {

        @Bean
        Job restartProbeJob(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
            return new JobBuilder(JOB_NAME, jobRepository)
                    .start(new StepBuilder("restart-probe-step", jobRepository)
                            .<Integer, Integer>chunk(2, transactionManager)
                            .reader(new RestartableReader())
                            .writer(items -> {
                                for (Integer item : items) {
                                    if (failOnThirdChunk && item == 5) {
                                        throw new IllegalStateException("일일 호출 한도 소진(가정)");
                                    }
                                    processed.add(item);
                                }
                            })
                            .build())
                    .build();
        }
    }

    /**
     * 처리 위치를 {@code ExecutionContext}에 저장하는 리더.
     *
     * <p>{@code ItemStream}을 구현해야 Spring Batch가 위치를 저장·복원한다. 이것이
     * 직접 만든 스케줄러와의 결정적 차이다 — 재개 지점 관리를 손으로 짜면 그것이 곧
     * 저품질 JobRepository가 된다.
     */
    static class RestartableReader implements ItemStreamReader<Integer> {

        private static final String POSITION_KEY = "restart.probe.position";
        private static final int TOTAL = 10;

        private int position;

        @Override
        public void open(ExecutionContext executionContext) {
            position = executionContext.getInt(POSITION_KEY, 0);
        }

        @Override
        public void update(ExecutionContext executionContext) {
            executionContext.putInt(POSITION_KEY, position);
        }

        @Override
        public Integer read() {
            return position >= TOTAL ? null : ++position;
        }
    }
}
