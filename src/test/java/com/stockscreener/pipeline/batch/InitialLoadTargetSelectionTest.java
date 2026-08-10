package com.stockscreener.pipeline.batch;

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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 초기 적재의 <b>대상 선정</b>만 검증한다.
 *
 * <p>고정하려는 것은 하나다 — <b>티커를 주면 그 종목이 실제로 대상이 되는가.</b>
 * 기본 경로의 {@code NOT EXISTS}는 봉이 한 개라도 있으면 종목을 영원히 건너뛰므로,
 * 신규 편입 종목이 일일 갱신으로 1~2봉만 받은 상태에서는 <b>기간을 늘려도 대상이 0건이
 * 되고 잡은 성공으로 끝난다.</b> 이 테스트가 없으면 {@code --tickers}를 교집합으로
 * 구현해도 아무것도 깨지지 않는데, 그 구현은 정확히 같은 조용한 0건을 만든다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). 대신 {@code @AfterEach}로 직접 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestDatabaseGuard.class, InitialLoadTargetSelectionTest.FakeSourceConfig.class})
class InitialLoadTargetSelectionTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 11);
    private static final String UNIVERSE = "KOSPI_TOP200";

    @Autowired
    private InitialLoadJob job;

    @Autowired
    private FakePriceDataSource fakePriceSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 봉이 하나 있어 {@code NOT EXISTS}가 영원히 건너뛰는 종목. 신규 편입의 실제 모습이다. */
    private long partiallyLoadedId;

    /** 봉이 하나도 없는 종목. 기본 경로가 잡는 유일한 대상이다. */
    private long emptyId;

    @BeforeEach
    void seed() {
        cleanUp();
        partiallyLoadedId = insertStock("082640", "동양생명");
        emptyId = insertStock("005930", "삼성전자");
        joinUniverse(partiallyLoadedId);
        joinUniverse(emptyId);
        insertSingleBar(partiallyLoadedId);
        fakePriceSource.reset();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        // MUST: 자식 테이블을 stock보다 먼저 지운다. 남으면 DELETE FROM stock이 FK 위반으로
        // 실패하고, 정리가 중단되면 종목이 커밋된 채 남아 같은 티커를 쓰는 다른 테스트가
        // 전부 깨진다 — 증상이 이 테스트와 무관해 보인다(.claude/rules/test.md).
        jdbcTemplate.update("DELETE FROM indicator_value");
        jdbcTemplate.update("DELETE FROM financial_statement");
        jdbcTemplate.update("DELETE FROM price_daily");
        jdbcTemplate.update("DELETE FROM universe_membership");
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('082640', '005930')");
    }

    @Test
    @DisplayName("티커를 주면 봉이 이미 있어도 대상이 된다 — NOT EXISTS를 교집합으로 걸지 않는다")
    void tickersBypassTheNotExistsFilter() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                new InitialLoadJob.LoadRange(7, 3, false, List.of("082640")));

        assertThat(fakePriceSource.fetchedStockIds).containsExactly(partiallyLoadedId);
    }

    @Test
    @DisplayName("티커를 주면 그 종목만 받는다 — 나머지 편입 종목은 건드리지 않는다")
    void tickersNarrowTheTargetSet() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                new InitialLoadJob.LoadRange(7, 3, false, List.of("082640")));

        assertThat(fakePriceSource.fetchedStockIds).doesNotContain(emptyId);
    }

    @Test
    @DisplayName("티커가 하나도 매칭되지 않으면 조용히 성공하지 않고 실패한다")
    void failsLoudlyWhenNoTickerMatches() {
        assertThatThrownBy(() -> job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                new InitialLoadJob.LoadRange(7, 3, false, List.of("999999"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999");

        assertThat(fakePriceSource.fetchedStockIds).isEmpty();
    }

    @Test
    @DisplayName("티커가 없으면 기본 경로 그대로다 — 봉이 있는 종목은 건너뛴다")
    void withoutTickersTheDefaultPathIsUnchanged() {
        job.run(AS_OF, BatchExecutionRecorder.TriggeredBy.MANUAL,
                InitialLoadJob.LoadRange.defaults());

        assertThat(fakePriceSource.fetchedStockIds).containsExactly(emptyId);
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

    /** 일일 갱신이 신규 편입 종목에 남긴 한 봉을 재현한다. */
    private void insertSingleBar(long stockId) {
        jdbcTemplate.update("""
                INSERT INTO price_daily
                    (stock_id, trade_date, open, high, low, close, adj_close, volume, listed_shares)
                VALUES (?, ?, 8250, 8480, 8250, 8250, 8250, 869055, 1000000)
                """, stockId, AS_OF.minusDays(5));
    }

    /**
     * 시세·재무 소스를 Fake로 등록한다.
     *
     * <p>실제 어댑터는 {@code @ConditionalOnApiKey}라 테스트에서 등록되지 않으므로 같은 시장에
     * 두 소스가 생기는 충돌은 없다.
     */
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
