package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.collector.CorporateActionCollector;
import com.stockscreener.pipeline.collector.PriceCalendar;
import com.stockscreener.pipeline.collector.PriceWriter;
import com.stockscreener.pipeline.collector.StockRegistrar;
import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.support.FakeCorporateActionDataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기업 행위 수집 잡을 검증한다(ADR-0049).
 *
 * <p>고정하려는 것이 셋이고 전부 <b>어겨도 초록인</b> 종류다. <b>키가 없을 때 0건으로 성공하지
 * 않는가</b>(그러면 수정계수가 영영 1로 남는데 로그도 알림도 없다), <b>봉이 없는 종목에 헛콜을
 * 태우지 않는가</b>, 그리고 <b>이 잡이 계수를 건드리지 않는가</b>이다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). 대신 {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, CorporateActionJobTest.FakeCorporateActionConfig.class})
class CorporateActionJobTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 12);
    private static final String UNIVERSE = "KOSPI200";

    @Autowired
    private CorporateActionJob job;

    @Autowired
    private FakeCorporateActionDataSource fakeSource;

    @Autowired
    private CorporateActionCollector collector;

    @Autowired
    private StockRegistrar stockRegistrar;

    @Autowired
    private PriceCalendar priceCalendar;

    @Autowired
    private BatchExecutions batchExecutions;

    @Autowired
    private PriceWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberId;

    @BeforeEach
    void seed() {
        cleanUp();
        memberId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, memberId, AS_OF.minusYears(1));
        fakeSource.fetchedStockIds.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        jdbcTemplate.update("DELETE FROM corporate_action");
        jdbcTemplate.update("DELETE FROM price_daily");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('005930', 'NVDA')");
    }

    @Test
    @DisplayName("공시를 원장에 적는다 — 창 기본값이 저장된 시세 전 구간이라 옛 사건도 든다")
    void recordsDisclosedActionsIntoTheLedger() {
        seedBars();
        fakeSource.with(memberId, split("2026-02-04"));

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(ledgerRowCount()).isEqualTo(1);
        assertThat(statusOf("2026-02-04")).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("이 잡은 수정계수를 건드리지 않는다 — rebuild()는 되돌릴 수 없는 유일한 지점이다")
    void neverTouchesTheAdjustmentFactor() {
        // 픽스처 조건이 중요하다. APPROVED가 될 非UNKNOWN 사건 하나와 **그보다 이른 봉**이
        // 있어야 rebuild()가 실제로 값을 바꾼다 — 없으면 몰래 불러도 이 검사가 초록이다.
        seedBars();
        fakeSource.with(memberId, split("2026-02-04"));

        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(statusOf("2026-02-04")).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM price_daily WHERE stock_id = ? AND adj_factor <> 1",
                Integer.class, memberId))
                .as("계수가 바뀌었다면 이 잡이 rebuild()를 불렀다는 뜻이다")
                .isZero();
    }

    @Test
    @DisplayName("담당 소스가 없으면 종목 루프를 돌기 전에 실패한다 — 0건 성공이면 계수가 영영 1로 남는다")
    void failsBeforeTheLoopWhenNoSourceIsRegistered() {
        seedBars();
        DataSourceRegistry empty = new DataSourceRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(),
                List.of());
        CorporateActionJob jobWithoutSource = new CorporateActionJob(
                collector, stockRegistrar, priceCalendar, empty, batchExecutions);

        BatchStatus status = jobWithoutSource.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.FAILED);
        // 루프를 돌았다면 종목마다 NOT_FOUND가 실패로 기록됐을 것이다. 그 0이
        // "루프 앞에서 막았다"의 실제 증거다 — 소스 호출 수로는 구분되지 않는다.
        assertThat(failureCount()).isZero();
        assertThat(ledgerRowCount()).isZero();
    }

    @Test
    @DisplayName("봉이 없는 종목에는 콜을 태우지 않는다 — 어댑터가 우리 시계열에서 값을 유도한다")
    void doesNotCallTheSourceForStocksWithoutBars() {
        // 봉은 다른 종목에만 있다. 전역 가드는 통과하고 종목 가드가 걸려야 한다.
        long other = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '000660', 'SK하이닉스')
                RETURNING id
                """, Long.class);
        writer.write(other, List.of(bar("2026-02-02", "1000")));

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(fakeSource.fetchedStockIds).isEmpty();

        jdbcTemplate.update("DELETE FROM price_daily WHERE stock_id = ?", other);
        jdbcTemplate.update("DELETE FROM stock WHERE id = ?", other);
    }

    @Test
    @DisplayName("저장된 시세가 하나도 없으면 소스를 부르지 않고 끝낸다 — 시세가 먼저다")
    void doesNothingWhenNoPriceIsStored() {
        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of(), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(fakeSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("티커가 하나도 매칭되지 않으면 조용히 성공하지 않고 실패한다")
    void failsLoudlyWhenNoTickerMatches() {
        seedBars();

        assertThatThrownBy(() -> job.run(
                AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, null, List.of("999999"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999");

        assertThat(fakeSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("창의 시작이 대상일보다 늦으면 즉시 실패하고 그 실패가 기록된다")
    void rejectsAnInvertedWindow() {
        seedBars();

        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                AS_OF.plusDays(1), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);

        // IMPORTANT — 검증이 실행 행보다 앞에 있으면 batch_execution에 아무 흔적도 안 남는다.
        // 관리자 화면으로 띄운 경우 던진 예외를 받을 호출부가 없어(런처가 비동기로 제출한다)
        // 화면에는 202 뒤 아무 일도 안 일어난 것으로 보인다.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM batch_execution WHERE job_name = ?
                ORDER BY id DESC LIMIT 1
                """, String.class, CorporateActionJob.JOB_NAME))
                .as("나쁜 창으로 끝난 실행이 기록되지 않았다")
                .isEqualTo("FAILED");
    }

    /** 불연속이 없는 봉 셋. 휴리스틱이 끼어들지 않아 공시 경로만 재게 된다. */
    private void seedBars() {
        writer.write(memberId, List.of(
                bar("2026-02-02", "1000"), bar("2026-02-03", "1010"), bar("2026-02-04", "1020")));
    }

    private static CorporateAction split(String date) {
        return new CorporateAction(LocalDate.parse(date), CorporateAction.Type.SPLIT,
                new BigDecimal("5"), CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
    }

    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null,
                new BigDecimal("1000000"), false);
    }

    private int ledgerRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM corporate_action", Integer.class);
    }

    @Test
    @DisplayName("유니버스를 손으로 주면 비활성이어도 그 종목을 고른다 — 활성 조인을 안 탄다")
    void picksTheChosenUniverseEvenWhenItIsInactive() {
        seedBars();
        long usStockId = seedInactiveUniverseMember();

        BatchStatus status = job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                LocalDate.of(2020, 1, 2), List.of(), "US_LISTED");

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);
        assertThat(fakeSource.fetchedStockIds)
                .as("US_LISTED는 비활성이라 활성 유니버스 경로로는 하나도 안 나온다")
                .containsExactly(usStockId);
        assertThat(fakeSource.fetchedStockIds)
                .as("활성 유니버스의 국내 종목은 대상이 아니다 — 손으로 고른 유니버스 하나만 본다")
                .doesNotContain(memberId);
    }

    @Test
    @DisplayName("고른 유니버스에 대상이 0건이면 예외다 — 조용한 0건은 「적재할 것이 없다」와 같다")
    void failsWhenTheChosenUniverseHasNoTargets() {
        seedBars();

        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                null, List.of(), "SP500"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대상이 하나도 없다");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM batch_execution", Integer.class))
                .as("대상을 못 고른 실행은 행을 열지 않는다")
                .isZero();
    }

    /** 비활성 유니버스에 든 미국 종목 하나. 봉을 함께 넣는다 — 없으면 수집기가 건너뛴다. */
    private long seedInactiveUniverseMember() {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('US', 'NVDA', 'NVIDIA')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('US_LISTED', ?, ?)
                """, id, AS_OF.minusYears(1));
        writer.write(id, List.of(bar("2026-02-02", "1000"), bar("2026-02-03", "1010")));
        return id;
    }

    private String statusOf(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM corporate_action WHERE effective_date = ?",
                String.class, LocalDate.parse(date));
    }

    private int failureCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM batch_execution_failure", Integer.class);
    }

    @TestConfiguration
    static class FakeCorporateActionConfig {

        @Bean
        FakeCorporateActionDataSource fakeCorporateActionDataSource() {
            return new FakeCorporateActionDataSource("KOSPI", "KOSDAQ", "US");
        }
    }
}
