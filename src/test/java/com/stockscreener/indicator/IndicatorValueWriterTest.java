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
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지표 쓰기가 <b>바뀐 것만</b> 쓰는지 검증한다.
 *
 * <p>이 성질이 없으면 조용히 비싸진다. 러너가 매일 lookback 창 전체를 재계산해 거의 같은 값을
 * 다시 쓰고, 그중 새 버전이 같은 페이지에 못 들어간 것들이 인덱스 3개에 새 엔트리를 만든다
 * (이 테이블은 fillfactor가 기본값 100이라 여유가 없다). 실제로 1.39M행짜리 테이블에 누적
 * 갱신 2억 3천만 건이 쌓여 인덱스만 1,987MB가 됐고, 그동안 어떤 테스트도 깨지지 않았다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TestDatabaseGuard.class)
class IndicatorValueWriterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 5);

    @Autowired
    private IndicatorValueWriter writer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long stockId;

    @BeforeEach
    void insertStock() {
        stockId = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '000001', '종목')
                RETURNING id
                """, Long.class);
    }

    @Test
    @DisplayName("같은 값을 다시 쓰면 0행이다 — 재계산이 인덱스를 부풀리지 않는 근거다")
    void doesNotRewriteUnchangedValues() {
        List<IndicatorPoint> points = List.of(
                new IndicatorPoint(AS_OF.minusDays(1), new BigDecimal("10.5")),
                new IndicatorPoint(AS_OF, new BigDecimal("11.5")));

        assertThat(writer.write(stockId, PerCalculator.CODE, points)).isEqualTo(2);
        assertThat(writer.write(stockId, PerCalculator.CODE, points))
                .as("값이 그대로면 행 버전을 새로 만들 이유가 없다")
                .isZero();
    }

    @Test
    @DisplayName("값이 달라진 행만 쓴다 — 원자료 정정이 반영되지 않으면 안 된다")
    void writesOnlyChangedValues() {
        writer.write(stockId, PerCalculator.CODE, List.of(
                new IndicatorPoint(AS_OF.minusDays(1), new BigDecimal("10.5")),
                new IndicatorPoint(AS_OF, new BigDecimal("11.5"))));

        long written = writer.write(stockId, PerCalculator.CODE, List.of(
                new IndicatorPoint(AS_OF.minusDays(1), new BigDecimal("10.5")),
                new IndicatorPoint(AS_OF, new BigDecimal("99.9"))));

        assertThat(written).isEqualTo(1);
        assertThat(valueOn(AS_OF)).isEqualByComparingTo("99.9");
    }

    @Test
    @DisplayName("건드리지 않은 행은 updated_at도 그대로다 — 무엇이 언제 바뀌었는지의 근거가 된다")
    void leavesUpdatedAtAloneWhenNothingChanged() {
        List<IndicatorPoint> points = List.of(new IndicatorPoint(AS_OF, new BigDecimal("10.5")));
        writer.write(stockId, PerCalculator.CODE, points);
        OffsetDateTime first = updatedAtOn(AS_OF);

        writer.write(stockId, PerCalculator.CODE, points);

        assertThat(updatedAtOn(AS_OF)).isEqualTo(first);
    }

    private BigDecimal valueOn(LocalDate tradeDate) {
        return jdbcTemplate.queryForObject("""
                SELECT value FROM indicator_value
                WHERE stock_id = ? AND trade_date = ? AND indicator_code = ?
                """, BigDecimal.class, stockId, tradeDate, PerCalculator.CODE);
    }

    private OffsetDateTime updatedAtOn(LocalDate tradeDate) {
        return jdbcTemplate.queryForObject("""
                SELECT updated_at FROM indicator_value
                WHERE stock_id = ? AND trade_date = ? AND indicator_code = ?
                """, OffsetDateTime.class, stockId, tradeDate, PerCalculator.CODE);
    }
}
