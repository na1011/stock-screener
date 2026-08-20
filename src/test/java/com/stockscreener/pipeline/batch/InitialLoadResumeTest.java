package com.stockscreener.pipeline.batch;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.pipeline.support.FakeFinancialDataSource;
import com.stockscreener.pipeline.support.FakePriceDataSource;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중단된 초기 적재를 <b>다시 돌렸을 때</b> 무엇이 보장되는가.
 *
 * <p>이 저장소의 재개는 <b>체크포인트가 아니라 멱등성</b>으로 선다. 잡은 매 실행마다 대상을
 * 도메인 데이터에서 새로 고르고({@code NOT EXISTS}), 이미 처리된 종목은 그래서 빠진다.
 * <b>중간 상태를 어디에도 저장하지 않는다</b> — {@code batch_execution_phase}는 관리자 화면의
 * 분모이지 재개 지점이 아니며(ADR-0037), 실행마다 새 행이 생긴다.
 *
 * <p>IMPORTANT — <b>이 성질을 재는 테스트가 없었다.</b> {@code JobRestartTest}가 그 자리에
 * 있는 것처럼 보였으나 실제로 재던 것은 <b>테스트가 자기 손으로 만든 Spring Batch 합성 잡</b>의
 * {@code ExecutionContext} 재개였고, 우리 잡은 한 번도 지나가지 않았다. 그 잡은 Spring Batch를
 * 걷어내며 함께 지웠다(ADR-0074 계열 결정). 여기가 그 자리를 받는다.
 *
 * <p>기존 테스트와 겹치지 않는 부분만 잰다. 대상 선정 자체는
 * {@code InitialLoadTargetSelectionTest}가, {@code ABORTED} 마감은
 * {@code BatchExecutionClosingTest}·{@code BatchExecutionShutdownHookTest}가, 종목 단위 격리는
 * {@code StockFailureIsolationTest}가 이미 잡는다. <b>여기서만 재는 것은 두 실행에 걸친
 * 합성 동작</b>이다.
 *
 * <p>MUST NOT: {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, InitialLoadResumeTest.FakeSourceConfig.class})
class InitialLoadResumeTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 11);
    private static final String UNIVERSE = "KOSPI_TOP200";

    @Autowired
    private InitialLoadJob job;

    @Autowired
    private FakePriceDataSource fakePriceSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 1차에서 성공하는 종목. 2차가 이것을 건드리면 안 된다. */
    private long doneId;

    /** 1차에서 쿼터로 실패하는 종목. 2차가 이것만 다시 잡아야 한다. */
    private long pendingId;

    @BeforeEach
    void seed() {
        cleanUp();
        doneId = insertStock("005930", "삼성전자");
        pendingId = insertStock("000660", "SK하이닉스");
        joinUniverse(doneId);
        joinUniverse(pendingId);

        fakePriceSource.reset();
        fakePriceSource.withBars(doneId, bars())
                .withBars(pendingId, bars())
                // 1차에서 쿼터가 바닥난 상황. 종목 단위로 격리되어 나머지는 저장된다(ADR-0007).
                .failingWith(pendingId, new DataSourceException(
                        DataSourceException.Kind.RATE_LIMITED, "fake-price", "일일 호출 한도 소진"));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution_phase");
        jdbcTemplate.update("DELETE FROM batch_execution");
        // MUST: 자식 테이블을 stock보다 먼저 지운다(.claude/rules/test.md).
        jdbcTemplate.update("DELETE FROM indicator_value");
        jdbcTemplate.update("DELETE FROM financial_statement");
        jdbcTemplate.update("DELETE FROM price_daily");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('005930', '000660')");
    }

    @Test
    @DisplayName("중단 뒤 다시 돌리면 처리한 종목의 시세가 늘지도 바뀌지도 않는다")
    void rerunIsIdempotentForAlreadyLoadedStocks() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, InitialLoadJob.LoadRange.defaults());
        Map<String, Object> first = priceFingerprint(doneId);

        fakePriceSource.clearFailure(pendingId);
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, InitialLoadJob.LoadRange.defaults());

        // 재개가 멱등성 위에 선다는 것이 이 단언의 내용이다. 행이 늘거나 값이 흔들리면
        // "다시 돌려도 안전하다"가 무너지고, 그때 필요한 것은 진짜 체크포인트다.
        assertThat(priceFingerprint(doneId))
                .as("이미 적재된 종목의 시세 지문(행 수·최종가·합계)")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("2차는 남은 종목만 부른다 — 성공한 종목에 쿼터를 다시 태우지 않는다")
    void rerunDoesNotSpendQuotaOnFinishedStocks() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, InitialLoadJob.LoadRange.defaults());

        fakePriceSource.clearFailure(pendingId);
        fakePriceSource.fetchedStockIds.clear();
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, InitialLoadJob.LoadRange.defaults());

        // 쿼터가 이 잡의 제약이다. 성공 종목을 다시 부르면 남은 것을 받을 예산이 그만큼 준다 —
        // Spring Batch의 skip 정책을 쓰지 않은 이유가 정확히 이것이다(ADR-0007).
        assertThat(fakePriceSource.fetchedStockIds)
                .as("2차가 시세를 부른 종목")
                .containsExactly(pendingId);
    }

    @Test
    @DisplayName("2차는 이어붙지 않고 새 실행이다 — 단계 기록은 체크포인트가 아니다")
    void rerunStartsAFreshExecutionInsteadOfResuming() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, InitialLoadJob.LoadRange.defaults());
        fakePriceSource.clearFailure(pendingId);
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL, InitialLoadJob.LoadRange.defaults());

        List<Long> executionIds = jdbcTemplate.queryForList(
                "SELECT id FROM batch_execution ORDER BY id", Long.class);
        assertThat(executionIds).as("실행 행").hasSize(2);

        // batch_execution_phase의 PK가 (execution_id, phase)다. 재실행은 이어붙는 것이 아니라
        // 자기 행을 새로 만든다 — 그러므로 "어디까지 했는가"를 여기서 읽을 수 없다.
        // MUST NOT: 이 단언을 "단계가 이어진다"로 뒤집지 않는다. 그렇게 읽으면 재개가
        // 진행률 투영에 얹혀 있다고 믿게 되는데, 실제로 얹혀 있는 것은 멱등성이다.
        for (Long executionId : executionIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM batch_execution_phase WHERE execution_id = ?",
                    Integer.class, executionId))
                    .as("실행 %d의 단계 기록", executionId)
                    .isPositive();
        }
    }

    private Map<String, Object> priceFingerprint(long stockId) {
        return jdbcTemplate.queryForMap("""
                SELECT count(*) AS rows, max(trade_date) AS last_date,
                       sum(close) AS close_sum, sum(volume) AS volume_sum
                FROM price_daily WHERE stock_id = ?
                """, stockId);
    }

    private List<RawPriceBar> bars() {
        return List.of(bar("2026-08-07", 100), bar("2026-08-10", 110), bar("2026-08-11", 120));
    }

    private RawPriceBar bar(String date, int close) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }

    private long insertStock(String ticker, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?) RETURNING id
                """, Long.class, ticker, name);
    }

    private void joinUniverse(long stockId) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, UNIVERSE, stockId, AS_OF.minusDays(4));
    }

    @TestConfiguration
    static class FakeSourceConfig {

        @Bean
        FakePriceDataSource fakePriceDataSource() {
            return new FakePriceDataSource("KOSPI", "KOSDAQ");
        }

        @Bean
        FakeFinancialDataSource fakeFinancialDataSource() {
            return new FakeFinancialDataSource("KOSPI", "KOSDAQ");
        }
    }
}
