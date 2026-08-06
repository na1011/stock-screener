package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.Atr14Calculator;
import com.stockscreener.indicator.calculator.MacdCalculator;
import com.stockscreener.indicator.calculator.MacdVCalculator;
import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.indicator.calculator.Rsi14Calculator;
import com.stockscreener.indicator.calculator.Sma20Calculator;
import com.stockscreener.indicator.calculator.Sma200Calculator;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지표 계산 엔진의 완료 판정.
 *
 * <p>합성 시세로 검증하므로 초기 적재와 무관하게 성립한다. 실데이터는 값의 분포를 볼 때
 * 필요하지 저 구조가 도는지를 볼 때 필요한 것이 아니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class IndicatorRunnerTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    @Autowired
    private IndicatorRunner runner;

    @Autowired
    private IndicatorRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '005930', '삼성전자')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("시세가 충분하면 기술적 지표를 계산해 저장한다")
    void computesAndStoresTechnicalIndicators() {
        insertPriceHistory(300);

        long written = runner.run(stockId, AS_OF);

        assertThat(written).isPositive();
        assertThat(storedCodes()).contains(
                Sma20Calculator.CODE, Sma200Calculator.CODE,
                Rsi14Calculator.CODE, Atr14Calculator.CODE, MacdCalculator.CODE);
    }

    @Test
    @DisplayName("이력이 부족한 지표는 행을 만들지 않는다 — NULL 행 대신 부재로 표현한다")
    void writesNoRowWhenHistoryIsInsufficient() {
        // 20일선은 되지만 200일선은 안 되는 길이다.
        insertPriceHistory(30);

        runner.run(stockId, AS_OF);

        assertThat(storedCodes())
                .contains(Sma20Calculator.CODE)
                .doesNotContain(Sma200Calculator.CODE);
    }

    @Test
    @DisplayName("파생 지표는 부모의 계산을 재구현하지 않고 이미 계산된 시계열을 받는다")
    void derivedIndicatorConsumesParentSeries() {
        insertPriceHistory(300);

        runner.run(stockId, AS_OF);

        assertThat(registry.find(MacdVCalculator.CODE)).isPresent();
        assertThat(registry.executionOrder().stream().map(IndicatorCalculator::code).toList())
                .as("위상 정렬이 부모를 먼저 놓아야 자식이 값을 받을 수 있다")
                .satisfies(order -> {
                    assertThat(order.indexOf(MacdVCalculator.CODE))
                            .isGreaterThan(order.indexOf(MacdCalculator.CODE));
                    assertThat(order.indexOf(MacdVCalculator.CODE))
                            .isGreaterThan(order.indexOf(Atr14Calculator.CODE));
                });
        assertThat(storedCodes())
                .as("부모가 모두 계산됐으므로 MACD-V도 값을 낸다")
                .contains(MacdVCalculator.CODE);
    }

    @Test
    @DisplayName("두 번 실행해도 행이 늘지 않는다 — 멱등해야 한다")
    void isIdempotent() {
        insertPriceHistory(300);

        runner.run(stockId, AS_OF);
        int afterFirst = countIndicatorValues();
        runner.run(stockId, AS_OF);

        assertThat(countIndicatorValues()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("재무가 없으면 재무 지표는 저장하지 않고 기술적 지표는 정상 저장한다")
    void skipsFundamentalsWithoutFinancials() {
        insertPriceHistory(300);

        runner.run(stockId, AS_OF);

        assertThat(storedCodes())
                .contains(Sma200Calculator.CODE)
                .doesNotContain(PerCalculator.CODE);
    }

    @Test
    @DisplayName("시세가 없으면 아무것도 저장하지 않는다")
    void storesNothingWithoutPrices() {
        assertThat(runner.run(stockId, AS_OF)).isZero();
        assertThat(countIndicatorValues()).isZero();
    }

    @Test
    @DisplayName("기술적 지표는 수정주가로 계산한다 — 분할 구간에서 원주가를 쓰면 값이 튄다")
    void technicalIndicatorsUseAdjustedClose() {
        // 원주가는 2배로 두고 수정주가만 일정하게 둔다. 수정주가를 쓰면 이동평균이
        // 그 일정한 값과 같아야 하고, 원주가를 썼다면 2배로 나온다.
        for (int i = 0; i < 300; i++) {
            LocalDate date = AS_OF.minusDays(300L - i);
            insertBar(date, new BigDecimal("2000"), new BigDecimal("1000"));
        }

        runner.run(stockId, AS_OF);

        BigDecimal sma = jdbcTemplate.queryForObject("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ? AND trade_date = ?
                """, BigDecimal.class, stockId, Sma20Calculator.CODE, AS_OF.minusDays(1));

        assertThat(sma).isEqualByComparingTo("1000");
    }

    private void insertPriceHistory(int days) {
        for (int i = 0; i < days; i++) {
            LocalDate date = AS_OF.minusDays((long) days - i);
            // 완만하게 오르내리게 해서 RSI·볼린저의 분모가 0이 되지 않게 한다.
            BigDecimal close = new BigDecimal(1000 + (i % 7) * 10);
            insertBar(date, close, close);
        }
    }

    private void insertBar(LocalDate date, BigDecimal close, BigDecimal adjClose) {
        jdbcTemplate.update("""
                INSERT INTO price_daily
                    (stock_id, trade_date, open, high, low, close, adj_close, adj_factor, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1000)
                """,
                stockId, date, close, close, close, close, adjClose);
    }

    private List<String> storedCodes() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT indicator_code FROM indicator_value WHERE stock_id = ?",
                String.class, stockId);
    }

    private int countIndicatorValues() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM indicator_value WHERE stock_id = ?", Integer.class, stockId);
    }
}
