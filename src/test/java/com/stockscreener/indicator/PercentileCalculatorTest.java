package com.stockscreener.indicator;

import com.stockscreener.indicator.calculator.PerCalculator;
import com.stockscreener.support.TestDatabaseGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("유니버스마다 따로 매긴다 — 합치면 코스피 최고값이 1이 되지 못한다")
    void ranksWithinEachUniverse() {
        // 코스피200은 PER 10~50이다. 그보다 전부 높은 코스닥 모집단을 따로 만든다.
        List<Long> kosdaq = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            kosdaq.add(memberWithPer("KOSDAQ", "KOSDAQ150", "10%04d".formatted(i),
                    new BigDecimal(i * 100)));
        }

        calculator.calculate(AS_OF);

        // 한 모집단으로 합치면 최고값은 PER 300(코스닥)뿐이라 아래 둘이 동시에 성립할 수 없다.
        assertThat(percentileOf(stockIds.get(4)))
                .as("PER 50은 코스피200 안에서 최고값이다")
                .isEqualByComparingTo("1");
        assertThat(percentileOf(kosdaq.get(0)))
                .as("PER 100은 코스닥150 안에서 최저값이다 — 합쳤다면 0이 아니다")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("한 종목이 두 유니버스에 걸리면 조용히 한쪽을 버리지 않고 깨진다")
    void refusesToRankStockBelongingToTwoUniverses() {
        // indicator_value에는 유니버스 축이 없어 종목당 값이 하나뿐이다. 둘 중 어느 순위를
        // 남길지 고를 근거가 없으므로, 고르지 않고 깨지는 것이 옳다.
        for (int i = 1; i <= 2; i++) {
            memberWithPer("KOSDAQ", "KOSDAQ150", "10%04d".formatted(i), new BigDecimal(i * 100));
        }
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES ('KOSDAQ150', ?, ?)
                """, stockIds.get(0), AS_OF);

        // IMPORTANT — 올라오는 것이 BadSqlGrammarException이다. PostgreSQL이 내는 것은
        // cardinality_violation(SQLState 21000)인데 Spring이 21 계열을 문법 오류로 번역한다.
        // 로그에서 이 이름만 보면 SQL 오타를 찾으러 가게 되므로, 진짜 원인은 root cause에 있다.
        assertThatThrownBy(() -> calculator.calculate(AS_OF))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("cannot affect row a second time");
    }

    @Test
    @DisplayName("모집단이 하나면 백분위를 만들지 않는다 — 0은 '가장 싸다'로 읽힌다")
    void skipsSingleMemberPopulation() {
        long lonely = memberWithPer("KOSDAQ", "KOSDAQ150", "100001", new BigDecimal("7"));

        calculator.calculate(AS_OF);

        assertThat(jdbcTemplate.queryForList("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ?
                """, BigDecimal.class, lonely, PerCalculator.CODE + PercentileSpec.SUFFIX))
                .as("순위가 무의미한 값으로 종목이 선정되면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("값이 그대로면 다시 쓰지 않는다 — 재계산이 인덱스를 부풀리지 않는 근거다")
    void secondRunWritesNothingWhenValuesAreUnchanged() {
        assertThat(calculator.calculate(AS_OF)).isPositive();

        assertThat(calculator.calculate(AS_OF))
                .as("같은 값을 다시 쓰면 MVCC가 행 버전과 인덱스 엔트리를 새로 만든다")
                .isZero();
    }

    @Test
    @DisplayName("두 번 실행해도 행이 늘지 않는다")
    void isIdempotent() {
        calculator.calculate(AS_OF);
        int afterFirst = countPercentiles();
        calculator.calculate(AS_OF);

        assertThat(countPercentiles()).isEqualTo(afterFirst);
    }

    /** 지정한 유니버스에 편입된 종목 하나를 PER 값과 함께 만든다. */
    private long memberWithPer(String marketCode, String universeCode, String ticker,
                               BigDecimal per) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, marketCode, ticker, "종목" + ticker);
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from)
                VALUES (?, ?, ?)
                """, universeCode, id, AS_OF);
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                """, id, AS_OF, PerCalculator.CODE, per);
        return id;
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
