package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.notification.AlertSeverity;
import com.stockscreener.pipeline.collector.CorporateActionLedger;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.stockscreener.pipeline.support.Judgements.approved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재구축 잡의 <b>잡 계층</b>만 검증한다(ADR-0049).
 *
 * <p>계수 계산·부호·멱등은 {@code AdjustmentRecalculatorTest}가 이미 잡는다. 여기서 고정하는 것은
 * <b>그 계산이 지표를 지웠다는 사실이 사람에게 닿는가</b>다 — 무효화는 지우기만 하므로 백필이
 * 따라오지 않으면 그 종목이 화면에서 통째로 사라지는데 <b>에러도 경고도 나지 않는다</b>
 * (phase 기준 §P9). 잡이 성공으로 끝나는 것이 곧 "일이 끝났다"가 아닌 유일한 자리다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, AdjustmentRebuildJobTest.RecordingAlertConfig.class})
class AdjustmentRebuildJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 2, 5);
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 2, 4);
    private static final String TICKER = "900101";

    @Autowired
    private AdjustmentRebuildJob job;

    @Autowired
    private CorporateActionLedger ledger;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private RecordingAlertPort alertPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void seed() {
        cleanUp();
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, '재구축종목')
                RETURNING id
                """, Long.class, TICKER);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('KOSPI200', ?, ?)
                """, stockId, AS_OF.minusYears(1));
        // 효력일 **이전** 봉이 있어야 재구축이 실제로 값을 바꾼다. 없으면 갱신 0행이라
        // 무효화도 알림도 안 나고, 이 테스트가 무엇을 재는지 모르게 초록이 된다.
        writer.write(stockId, List.of(
                bar(LocalDate.of(2026, 2, 2), "50000"),
                bar(LocalDate.of(2026, 2, 3), "50000"),
                bar(EFFECTIVE, "10000")));
        long id = ledger.record(stockId, new CorporateAction(
                EFFECTIVE, CorporateAction.Type.SPLIT, new BigDecimal("5"),
                CorporateAction.DetectionSource.HEURISTIC)).existingId();
        ledger.judge(id, approved("5:1 분할. 주식수 배수로 확인했다."));
        alertPort.events.clear();
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
                DELETE FROM indicator_value WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker = ?)
                """, TICKER);
        jdbcTemplate.update("""
                DELETE FROM price_daily WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker = ?)
                """, TICKER);
        jdbcTemplate.update("""
                DELETE FROM universe_membership WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker = ?)
                """, TICKER);
        jdbcTemplate.update("DELETE FROM stock WHERE ticker = ?", TICKER);
    }

    @Test
    @DisplayName("지표를 지웠으면 백필이 남았다고 알린다 — 성공으로 끝나는 것이 일이 끝난 것이 아니다")
    void alertsThatBackfillIsStillPending() {
        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                List.of(TICKER));

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ? AND adj_factor <> 1",
                Integer.class, stockId))
                .as("효력일 이전 두 봉의 계수가 0.2가 되어야 이 테스트가 무언가를 잰다")
                .isEqualTo(2);

        assertThat(alertPort.events).hasSize(1);
        AlertEvent event = alertPort.events.get(0);
        assertThat(event.severity()).isEqualTo(AlertSeverity.WARNING);
        assertThat(event.body())
                .as("무엇을 이어서 해야 하는지가 본문에 없으면 이 알림은 상태 보고일 뿐이다")
                .contains("--job=backfill")
                .contains(TICKER);
    }

    @Test
    @DisplayName("두 번째 실행은 아무 행도 건드리지 않고 알리지도 않는다 — 알림이 매번 오면 아무도 안 본다")
    void secondRunChangesNothing() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(TICKER));
        alertPort.events.clear();

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, List.of(TICKER));

        // 억제기가 접은 것과 구분하기 위해 DB의 갱신 행 수로도 본다. 알림만 보면 "억제됐다"와
        // "바꿀 것이 없었다"가 같아 보이고, 전자면 무효화는 매번 일어난 것이다.
        assertThat(writtenRowsOfLatestRun())
                .as("2회차가 행을 건드렸다면 계수를 대입이 아니라 곱셈으로 되돌린 것이다")
                .isZero();
        assertThat(alertPort.events).isEmpty();
    }

    @Test
    @DisplayName("티커가 하나도 안 맞으면 0종목 성공이 아니라 예외다 — 재실행에서 0행이 정상이라 구분되지 않는다")
    void unknownTickerFailsInsteadOfSucceedingWithNoTargets() {
        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                List.of("999999")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("편입 중인 활성 종목이 하나도 없다");
    }

    private Long writtenRowsOfLatestRun() {
        return jdbcTemplate.queryForObject("""
                SELECT written_row_count FROM batch_execution
                WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, Long.class, AdjustmentRebuildJob.JOB_NAME);
    }

    private static RawPriceBar bar(LocalDate date, String close) {
        BigDecimal price = new BigDecimal(close);
        return new RawPriceBar(date, price, price, price, price,
                1_000_000L, null, null, false);
    }

    /** 알림을 실제로 보내는 대신 모아둔다. 요약 한 건인지 세어보기 위함이다. */
    static class RecordingAlertPort implements AlertPort {
        final List<AlertEvent> events = new ArrayList<>();

        @Override
        public void send(AlertEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration
    static class RecordingAlertConfig {
        @Bean
        @Primary
        RecordingAlertPort recordingAlertPort() {
            return new RecordingAlertPort();
        }
    }
}
