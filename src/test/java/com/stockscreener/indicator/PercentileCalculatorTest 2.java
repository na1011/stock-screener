package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.PerCalculator;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백분위 산출을 검증한다.
 *
 * <p>백분위의 존재 이유는 "PER 하위 20%" 같은 상대 조건을 <b>평범한 스칼라 비교</b>로
 * 내리는 것이다. 그래야 조건 번역기가 상대 조건을 특별하게 다루지 않는다(ADR-0003).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class PercentileCalculatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    @Autowired
    private PercentileCalculator calculator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> stockIds = new ArrayList<>();

    @BeforeEach
    void insertUniverseWithPerValues() {
        // PER 10, 20, 30, 40, 50인 5종목. 모두 코스피200에 편입시킨다.
        for (int i = 1; i <= 5; i++) {
            long id = jdbcTemplate.queryForObject("""
                    INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', ?, ?)
                    RETURNING id
                    """, Long.class, String.format("%06d", i), "종목" + i);
            stockIds.add(id);
            jdbcTemplate.update("""
                    INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                    VALUES ('KOSPI200', ?, ?)
                    """, id, AS_OF);
            jdbcTemplate.update("""
                    INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                    VALUES (?, ?, ?, ?)
                    """, id, AS_OF, PerCalculator.CODE, new BigDecimal(i * 10));
        }
    }

    @Test
    @DisplayName("가장 낮은 값이 0, 가장 높은 값이 1이다 — 항상 오름차순 기준이다")
    void ranksAscending() {
        calculator.calculate(AS_OF);

        assertThat(percentileOf(stockIds.get(0)))
                .as("PER 10은 최저값이므로 0")
                .isEqualByComparingTo("0");
        assertThat(percentileOf(stockIds.get(4)))
                .as("PER 50은 최고값이므로 1")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("중간값은 순위에 비례한다")
    void ranksProportionally() {
        calculator.calculate(AS_OF);

        // PERCENT_RANK는 (순위-1)/(전체-1)이다. 3번째 종목은 (3-1)/(5-1) = 0.5.
        assertThat(percentileOf(stockIds.get(2))).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("편입되지 않은 종목은 모집단에서 빠진다 — 순위의 기준이 유니버스여야 한다")
    void excludesNonMembers() {
        long outsider = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '999999', '비편입')
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, 1)
                """, outsider, AS_OF, PerCalculator.CODE);

        calculator.calculate(AS_OF);

        assertThat(jdbcTemplate.queryForList("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ?
                """, BigDecimal.class, outsider, PerCalculator.CODE + PercentileSpec.SUFFIX))
                .as("비편입 종목에는 백분위를 만들지 않는다")
                .isEmpty();
        assertThat(percentileOf(stockIds.get(0)))
                .as("PER 1인 비편입 종목이 모집단에 들어갔다면 최저값이 바뀌었을 것이다")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("두 번 실행해도 행이 늘지 않는다")
    void isIdempotent() {
        calculator.calculate(AS_OF);
        int afterFirst = countPercentiles();
        calculator.calculate(AS_OF);

        assertThat(countPercentiles()).isEqualTo(afterFirst);
    }

    private BigDecimal percentileOf(long stockId) {
        return jdbcTemplate.queryForObject("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND trade_date = ? AND indicator_code = ?
                """, BigDecimal.class, stockId, AS_OF, PerCalculator.CODE + PercentileSpec.SUFFIX);
    }

    private int countPercentiles() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM indicator_value WHERE indicator_code = ?
                """, Integer.class, PerCalculator.CODE + PercentileSpec.SUFFIX);
    }
}
