package com.stockscreener.pipeline.batch;

import com.stockscreener.indicator.PercentileSpec;
import com.stockscreener.indicator.calculator.PerCalculator;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 종목을 좁힌 백필이 <b>순위의 모집단을 좁히지 않는지</b> 고정한다.
 *
 * <p>phase 기준 §P9가 <i>"재계산이 순위의 모집단을 좁히지 않는다"</i>를 명시한다(ADR-0036과 같은
 * 자리다). {@code --tickers}가 필요한 이유는 무효화가 낀 재백필의 복구 비용이고
 * ({@code AdjustmentRecalculator.rebuild}가 그 종목의 보존 창을 통째로 비운다), 그 필터가
 * 백분위·업종 상대값까지 좁히면 <b>대상 밖 종목의 순위가 조용히 낡는다</b> — 값이 그럴듯해
 * 화면에서도 응답에서도 드러나지 않는다.
 *
 * <p><b>이 테스트는 항등식이 아니다.</b> 두 단언이 서로 반대 방향을 잡는다 — 필터가 아예 안
 * 걸리면 첫째가 깨지고({@code target_count}가 3이 된다), 필터가 백분위까지 좁히면 둘째가
 * 깨진다(대상 밖 종목에 {@code *_PCTL}이 없다). 하나만으로는 어느 쪽도 못 잡는다.
 *
 * <p>IMPORTANT — {@code @Transactional}을 쓰지 않는다. 종목 단위 격리가 {@code REQUIRES_NEW}로
 * 커밋된 트랜잭션을 요구한다(ADR-0007). {@code @AfterEach}로 직접 지운다.
 *
 * <p>편입 유니버스가 {@code KOSPI200}인 것이 중요하다 — {@code PercentileCalculator}가
 * {@code universe.is_active}를 조인하므로, 테스트 DB에서 비활성인 {@code KOSPI_TOP200}에 심으면
 * <b>백분위가 한 행도 안 나오고 둘째 단언이 이유 없이 깨진다</b>(`.claude/rules/test.md`).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
class IndicatorBackfillTickerScopeTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);
    private static final String UNIVERSE = "KOSPI200";
    private static final List<String> TICKERS = List.of("900001", "900002", "900003");

    @Autowired
    private IndicatorBackfillJob job;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        cleanUp();
        for (int i = 0; i < TICKERS.size(); i++) {
            String ticker = TICKERS.get(i);
            long id = jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?)
                    RETURNING id
                    """, Long.class, ticker, "종목" + ticker);
            jdbcTemplate.update("""
                    INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                    VALUES (?, ?, ?)
                    """, UNIVERSE, id, AS_OF.minusYears(1));
            // 거래일 목록이 price_daily에서 나오므로 봉이 없으면 잡이 "백필할 것이 없다"로 끝난다.
            jdbcTemplate.update("""
                    INSERT INTO price_daily
                        (stock_id, trade_date, open, high, low, close, adj_close, adj_factor,
                         volume, trade_value, listed_shares)
                    VALUES (?, ?, 1000, 1000, 1000, 1000, 1000, 1, 100, 100000, 1000000)
                    """, id, AS_OF);
            // 백분위의 입력. 대상 종목만 다시 계산해도 이 값들로 순위가 다시 매겨져야 한다.
            jdbcTemplate.update("""
                    INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                    VALUES (?, ?, ?, ?)
                    """, id, AS_OF, PerCalculator.CODE, new BigDecimal((i + 1) * 10));
        }
    }

    @AfterEach
    void cleanUp() {
        // MUST: indicator_value를 stock보다 먼저 지운다. FK로 정리가 중단되면 무관해 보이는
        // 다른 테스트가 깨진다(`.claude/rules/test.md`).
        jdbcTemplate.update("DELETE FROM batch_execution_failure");
        jdbcTemplate.update("DELETE FROM batch_execution");
        jdbcTemplate.update("""
                DELETE FROM indicator_value WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker IN ('900001','900002','900003'))
                """);
        jdbcTemplate.update("""
                DELETE FROM price_daily WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker IN ('900001','900002','900003'))
                """);
        jdbcTemplate.update("""
                DELETE FROM universe_membership WHERE stock_id IN
                    (SELECT id FROM stock WHERE ticker IN ('900001','900002','900003'))
                """);
        jdbcTemplate.update("DELETE FROM stock WHERE ticker IN ('900001','900002','900003')");
    }

    @Test
    @DisplayName("한 종목만 백필해도 대상 밖 종목의 백분위가 다시 매겨진다")
    void narrowingTargetsDoesNotNarrowThePercentilePopulation() {
        BatchStatus status = job.run(AS_OF, AS_OF,
                BatchExecutionRecorder.TriggeredBy.MANUAL, List.of("900001"), null);

        assertThat(status).isEqualTo(BatchStatus.SUCCESS);

        assertThat(targetCountOfLatestRun())
                .as("티커 필터가 실제로 걸렸는가 — 3이면 인자가 무시된 것이다")
                .isEqualTo(1);

        for (String outside : List.of("900002", "900003")) {
            assertThat(percentileOf(outside))
                    .as("%s은 대상이 아니지만 그날 순위는 전 종목에서 나와야 한다", outside)
                    .isNotNull();
        }
        // 순위가 실제로 셋을 모집단으로 삼았는지 값으로 본다. PER 10·20·30이면 오름차순
        // 백분위가 각각 0 · 0.5 · 1이다. 모집단이 대상 하나로 좁혀졌다면 population < 2라
        // 아무 행도 안 나오고 위 단언이 먼저 깨진다.
        assertThat(percentileOf("900001")).isEqualByComparingTo("0");
        assertThat(percentileOf("900003")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("티커가 하나도 안 맞으면 0종목 성공이 아니라 예외다")
    void unknownTickerFailsInsteadOfSucceedingWithNoTargets() {
        // 무효화 뒤 복구로 부르는 경로라 0종목 성공은 "그 종목이 화면에서 사라진 채로 끝났다"와
        // 같은 뜻이다. 오타가 그것과 구분되지 않으면 안 된다.
        assertThatThrownBy(() -> job.run(AS_OF, AS_OF,
                BatchExecutionRecorder.TriggeredBy.MANUAL, List.of("999999"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("편입 중인 활성 종목이 하나도 없다");
    }

    private Integer targetCountOfLatestRun() {
        return jdbcTemplate.queryForObject("""
                SELECT target_count FROM batch_execution
                WHERE job_name = ? ORDER BY id DESC LIMIT 1
                """, Integer.class, IndicatorBackfillJob.JOB_NAME);
    }

    private BigDecimal percentileOf(String ticker) {
        return jdbcTemplate.query("""
                SELECT v.value FROM indicator_value v JOIN stock s ON s.id = v.stock_id
                WHERE s.ticker = ? AND v.trade_date = ? AND v.indicator_code = ?
                """,
                (rs, rowNum) -> rs.getBigDecimal("value"),
                ticker, AS_OF, PerCalculator.CODE + PercentileSpec.SUFFIX).stream().findFirst().orElse(null);
    }
}
