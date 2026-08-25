package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.CorporateActionLedger;
import com.stockscreener.pipeline.collector.CorporateActionStatus;
import com.stockscreener.pipeline.collector.PriceWriter;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.stockscreener.pipeline.support.Judgements.approved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판정 CLI의 <b>잡 계층</b>만 검증한다(ADR-0084).
 *
 * <p>판정값의 불변식은 {@code CorporateActionLedgerTest}가 이미 잡는다. 여기서 고정하는 것은
 * <b>실패가 실행 행과 알림을 만들지 않는가</b>다 — 이 잡의 입력은 배치가 아니라 <b>사람이 친
 * argv</b>라서, 오타 하나가 운영자 알림 채널을 타면 진짜 장애가 그 노이즈에 묻힌다.
 * 그리고 그 실패는 {@code judge()}가 {@code @Transactional}이라 <b>아무것도 안 쓴 실패</b>다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 실행 기록이 {@code REQUIRES_NEW}로
 * 커밋되므로 롤백 격리로는 그 행을 볼 수 없다(ADR-0007). {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class CorporateActionJudgeJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 2, 5);
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 2, 4);
    private static final String TICKER = "900102";

    @Autowired
    private CorporateActionJudgeJob job;

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void seed() {
        cleanUp();
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, '판정종목')
                RETURNING id
                """, Long.class, TICKER);
        // 효력일 앞뒤로 봉이 있어야 리더가 관측 배수를 낸다. 없으면 증거가 통째로 null이라
        // 이 테스트가 무엇을 재는지 흐려진다.
        writer.write(stockId, List.of(
                bar(LocalDate.of(2026, 2, 3), "50000", "10000000"),
                bar(EFFECTIVE, "10000", "50000000")));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        jdbcTemplate.update("""
                DELETE FROM corporate_action WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker = ?)
                """, TICKER);
        jdbcTemplate.update("""
                DELETE FROM price_daily WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker = ?)
                """, TICKER);
        jdbcTemplate.update("DELETE FROM stock WHERE ticker = ?", TICKER);
    }

    @Test
    @DisplayName("판정이 원장을 고치고, 무엇을 판정했는지가 실행 행에 남는다")
    void judgementIsWrittenAndRecorded() {
        long id = candidate(CorporateAction.Type.SPLIT, "5");

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, id,
                approved("5:1 분할. 주식수 배수로 확인했다."));

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(statusOf(id)).isEqualTo(CorporateActionStatus.APPROVED.name());

        // 인자 없는 finish()로 돌아가면 이 단언이 깨진다 — 그때 실행 행은 "판정 잡이 한 번
        // 돌았다"까지만 말하고, 무엇을 판정했는지는 회전하는 앱 로그에만 남는다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT note FROM batch_execution WHERE job_name = ?
                """, String.class, CorporateActionJudgeJob.JOB_NAME))
                .contains("id=" + id)
                .contains("status=APPROVED");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT success_count FROM batch_execution WHERE job_name = ?
                """, Integer.class, CorporateActionJudgeJob.JOB_NAME))
                .as("countExternally를 빼면 제약도 상태도 초록인 채 「대상 1 / 성공 0」으로 남는다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("불변식을 깬 판정은 예외로 나오고 실행 행을 만들지 않는다 — 사람의 오타가 알림을 타면 안 된다")
    void invalidJudgementLeavesNoExecutionRow() {
        long id = candidate(CorporateAction.Type.UNKNOWN, "0.2");

        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, id,
                approved("종류를 모르는 채 승인해 본다")))
                .isInstanceOf(CorporateActionLedger.InvalidJudgementException.class);

        assertThat(statusOf(id))
                .as("judge()가 @Transactional이라 실패는 아무것도 안 쓴다")
                .isEqualTo(CorporateActionStatus.OBSERVED.name());
        assertThat(executionCount()).isZero();
    }

    @Test
    @DisplayName("없는 id도 예외이고 실행 행이 없다")
    void missingRowLeavesNoExecutionRow() {
        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                -1L, approved("없는 행")))
                .isInstanceOf(CorporateActionLedger.CorporateActionNotFoundException.class);

        assertThat(executionCount()).isZero();
    }

    @Test
    @DisplayName("조회는 원장을 한 글자도 고치지 않고 실행 행도 안 만든다")
    void reviewNeitherWritesNorRecords() {
        long id = candidate(CorporateAction.Type.SPLIT, "5");
        Instant before = updatedAt(id);

        assertThat(job.review(null, null, null, null, null)).isEqualTo(BatchStatus.SUCCESS);
        assertThat(job.review(null, null, null, id, null)).isEqualTo(BatchStatus.SUCCESS);

        assertThat(updatedAt(id)).isEqualTo(before);
        assertThat(statusOf(id)).isEqualTo(CorporateActionStatus.OBSERVED.name());
        assertThat(executionCount()).isZero();
    }

    @Test
    @DisplayName("없는 id를 조회해도 죽지 않는다 — 대기열을 훑는 도중 흔한 오타다")
    void reviewOfMissingRowIsNotAFailure() {
        assertThat(job.review(null, null, null, -1L, null)).isEqualTo(BatchStatus.SUCCESS);
    }

    /** 사람이 판정할 후보 하나. 휴리스틱이라 {@code OBSERVED}로 들어간다. */
    private long candidate(CorporateAction.Type type, String ratio) {
        return ledger.record(stockId, new CorporateAction(
                EFFECTIVE, type, new BigDecimal(ratio),
                CorporateAction.DetectionSource.HEURISTIC)).existingId();
    }

    private String statusOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM corporate_action WHERE id = ?", String.class, id);
    }

    private Instant updatedAt(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT updated_at FROM corporate_action WHERE id = ?", Instant.class, id);
    }

    private int executionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution", Integer.class);
    }

    /** 상장주식수를 싣는다 — 리더의 증거가 그 배수에서 나오므로 null이면 아무것도 안 재게 된다. */
    private static RawPriceBar bar(LocalDate date, String close, String shares) {
        BigDecimal price = new BigDecimal(close);
        return new RawPriceBar(date, price, price, price, price,
                1_000_000L, null, new BigDecimal(shares), false);
    }
}
