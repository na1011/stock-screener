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
        calculator.calculate(AS_OF, null);

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
        calculator.calculate(AS_OF, null);

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

        calculator.calculate(AS_OF, null);

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

        calculator.calculate(AS_OF, null);

        // 한 모집단으로 합치면 최고값은 PER 300(코스닥)뿐이라 아래 둘이 동시에 성립할 수 없다.
        assertThat(percentileOf(stockIds.get(4)))
                .as("PER 50은 코스피200 안에서 최고값이다")
                .isEqualByComparingTo("1");
        assertThat(percentileOf(kosdaq.get(0)))
                .as("PER 100은 코스닥150 안에서 최저값이다 — 합쳤다면 0이 아니다")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("비활성 유니버스는 모집단이 아니다 — 폐기된 지수의 편입 이력이 순위를 만들면 안 된다")
    void excludesInactiveUniverses() {
        // 이 저장소에서 KOSPI_TOP200은 테스트 DB에서 비활성이다(운영과 정반대다 — test.md).
        // 제외는 삭제가 아니므로 폐기된 지수에 편입 이력이 남아 있는 것 자체는 정상이다.
        //
        // MUST: 둘 이상 넣는다. 하나면 population >= 2가 먼저 걸러내 이 테스트가 is_active를
        // 지워도 통과한다 — 실제로 그렇게 써서 결함을 놓쳤다.
        long retired = memberWithPer("KOSPI", "KOSPI_TOP200", "100001", BigDecimal.ONE);
        memberWithPer("KOSPI", "KOSPI_TOP200", "100002", new BigDecimal("2"));

        calculator.calculate(AS_OF, null);

        assertThat(jdbcTemplate.queryForList("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND indicator_code = ?
                """, BigDecimal.class, retired, PerCalculator.CODE + PercentileSpec.SUFFIX))
                .as("아무도 보지 않는 모집단의 순위를 만들지 않는다")
                .isEmpty();
        assertThat(percentileOf(stockIds.get(0)))
                .as("PER 1인 비활성 편입 종목이 활성 모집단에 섞였다면 최저값이 바뀌었을 것이다")
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
        assertThatThrownBy(() -> calculator.calculate(AS_OF, null))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("cannot affect row a second time");
    }

    @Test
    @DisplayName("모집단이 하나면 백분위를 만들지 않는다 — 0은 '가장 싸다'로 읽힌다")
    void skipsSingleMemberPopulation() {
        long lonely = memberWithPer("KOSDAQ", "KOSDAQ150", "100001", new BigDecimal("7"));

        calculator.calculate(AS_OF, null);

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
        assertThat(calculator.calculate(AS_OF, null)).isPositive();

        assertThat(calculator.calculate(AS_OF, null))
                .as("같은 값을 다시 쓰면 MVCC가 행 버전과 인덱스 엔트리를 새로 만든다")
                .isZero();
    }

    @Test
    @DisplayName("두 번 실행해도 행이 늘지 않는다")
    void isIdempotent() {
        calculator.calculate(AS_OF, null);
        int afterFirst = countPercentiles();
        calculator.calculate(AS_OF, null);

        assertThat(countPercentiles()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("그날 편입이 아니었던 종목의 옛 백분위를 걷는다 — 원본이 그대로 있어도 걷는다")
    void deletesRowsOfStocksThatWereNotMembersOnThatDay() {
        // 시점화(ADR-0135) 전에는 「오늘 명부」로 과거 전 날짜에 백분위를 썼다. 그래서 편입
        // 이전 날짜에 남은 행이 실측 216,752개였다(ADR-0136).
        long joinedLater = memberWithPer("KOSPI", "KOSPI200", "100001", new BigDecimal("7"));
        jdbcTemplate.update("""
                UPDATE universe_membership SET effective_from = ? WHERE stock_id = ?
                """, AS_OF.plusDays(1), joinedLater);
        // MUST: 원본을 남겨 둔다. 원본까지 지우면 「원본이 없다」축이 삭제를 설명해 버려
        // 편입 축을 재는 시험이 아니게 된다 — 실측으로 그 216,752행은 **전부 원본이 있었다.**
        staleRow(joinedLater, PerCalculator.CODE + PercentileSpec.SUFFIX, "1", AS_OF);

        calculator.calculate(AS_OF, null);

        assertThat(valueAt(joinedLater, PerCalculator.CODE + PercentileSpec.SUFFIX, AS_OF))
                .as("그날 명부에 없던 종목의 순위는 다른 명부의 것이다")
                .isNull();
        assertThat(valueAt(joinedLater, PerCalculator.CODE, AS_OF))
                .as("원본은 이 삭제의 대상이 아니다 — 지우면 여집합을 잘못 잡은 것이다")
                .isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("모집단이 하나로 줄어든 자리의 옛 행도 걷는다 — 배제 사유가 편입 하나가 아니다")
    void deletesRowsWhenThePopulationFellBelowTwo() {
        long lonely = memberWithPer("KOSDAQ", "KOSDAQ150", "100002", new BigDecimal("7"));
        // 어제까지는 둘 이상이었다가 리밸런싱으로 하나가 된 상태다. 편입은 그날을 덮고 원본도
        // 있으므로, 술어를 편입 축으로만 좁히면 이 행이 남는다.
        staleRow(lonely, PerCalculator.CODE + PercentileSpec.SUFFIX, "0", AS_OF);

        calculator.calculate(AS_OF, null);

        assertThat(valueAt(lonely, PerCalculator.CODE + PercentileSpec.SUFFIX, AS_OF))
                .as("0은 '가장 싸다'로 읽혀 조건을 충족시킨다")
                .isNull();
    }

    @Test
    @DisplayName("값이 안 바뀐 재실행에서 편입 종목의 멀쩡한 행은 남는다 — 「이번에 안 썼다」를 기준으로 삼으면 안 된다")
    void keepsRowsOnRunsWhereTheUpsertWroteNothing() {
        calculator.calculate(AS_OF, null);
        assertThat(percentileOf(stockIds.get(2))).isEqualByComparingTo("0.5");

        // 두 번째 실행은 IS DISTINCT FROM 때문에 0행을 쓴다. 삭제 술어를 「UPSERT가 반환한
        // 행 수」로 잡으면 여기서 멀쩡한 행이 사라진다 — DerivedIndicatorCleanup의 javadoc이
        // 그 함정을 적어 뒀고, 이 단언이 그 경계를 지킨다. 자리표시자가 밀려 모집단이 비는
        // 사고도 여기서 드러난다.
        calculator.calculate(AS_OF, null);

        assertThat(percentileOf(stockIds.get(2)))
                .as("집합의 소속으로 판정하므로 값이 안 바뀐 날에도 그 종목은 모집단에 있다")
                .isEqualByComparingTo("0.5");
        assertThat(countPercentiles()).isEqualTo(5);
    }

    @Test
    @DisplayName("백필 유니버스를 준 실행은 그 비활성 유니버스 구성원의 행을 지우지 않는다")
    void keepsRowsOfTheBackfillUniverseWhenItsCodeIsGiven() {
        // 「켜야 지표가 생기고, 지표가 없으면 켜면 안 되는」 순환을 끊는 자리다. 삭제가 모집단을
        // 계산과 같은 것으로 보지 않으면, 미리 백필해 둔 값을 같은 실행이 도로 지운다.
        long backfilled = memberWithPer("KOSPI", "KOSPI_TOP200", "100003", BigDecimal.ONE);
        memberWithPer("KOSPI", "KOSPI_TOP200", "100004", new BigDecimal("2"));

        calculator.calculate(AS_OF, "KOSPI_TOP200");

        assertThat(valueAt(backfilled, PerCalculator.CODE + PercentileSpec.SUFFIX, AS_OF))
                .as("모집단에 넣기로 한 유니버스의 순위는 이 실행이 쓴 값이다")
                .isEqualByComparingTo("0");
        // 코드를 **안 주는** 실행은 이 행을 지운다. 그것이 지금 동작이고 ADR-0136 §결과가
        // 남는 위험으로 적어 뒀다 — 여기서 단언하지 않는 이유는 그것을 원해서가 아니다.
    }

    @Test
    @DisplayName("다른 거래일의 행은 건드리지 않는다")
    void doesNotTouchOtherTradeDates() {
        long outsider = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '999998', '비편입')
                RETURNING id
                """, Long.class);
        staleRow(outsider, PerCalculator.CODE + PercentileSpec.SUFFIX, "1", AS_OF);
        staleRow(outsider, PerCalculator.CODE + PercentileSpec.SUFFIX, "1", AS_OF.minusDays(1));

        calculator.calculate(AS_OF, null);

        assertThat(valueAt(outsider, PerCalculator.CODE + PercentileSpec.SUFFIX, AS_OF))
                .as("그날 모집단 밖이다")
                .isNull();
        assertThat(valueAt(outsider, PerCalculator.CODE + PercentileSpec.SUFFIX, AS_OF.minusDays(1)))
                .as("삭제 상한이 trade_date 하나로 묶여 있다 — 그 날짜는 이 실행이 판정하지 않았다")
                .isEqualByComparingTo("1");
    }

    /** 옛 규제로 쓰인 행을 흉내낸다. 계산기를 거치지 않고 직접 심는다. */
    private void staleRow(long stockId, String code, String value, LocalDate tradeDate) {
        jdbcTemplate.update("""
                INSERT INTO indicator_value (stock_id, trade_date, indicator_code, value)
                VALUES (?, ?, ?, ?)
                """, stockId, tradeDate, code, new BigDecimal(value));
    }

    private BigDecimal valueAt(long stockId, String code, LocalDate tradeDate) {
        return jdbcTemplate.queryForList("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND trade_date = ? AND indicator_code = ?
                """, BigDecimal.class, stockId, tradeDate, code).stream().findFirst().orElse(null);
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
