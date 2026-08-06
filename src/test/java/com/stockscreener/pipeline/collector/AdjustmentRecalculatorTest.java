package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.RawPriceBar;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 액면분할 재계산을 검증한다.
 *
 * <p>이 계산이 틀리면 기술적 지표 전체가 조용히 망가지고, 분할 종목의 차트를 직접 보기
 * 전까지 드러나지 않는다(ADR-0002).
 */
@JpaSliceTest
@Import({AdjustmentRecalculator.class, PriceWriter.class,
        NoOpIndicatorInvalidationPort.DefaultInvalidationPortConfig.class})
class AdjustmentRecalculatorTest {

    @Autowired
    private AdjustmentRecalculator recalculator;

    @Autowired
    private PriceWriter writer;

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
    @DisplayName("50:1 분할 시 분할 이전 봉만 1/50로 조정한다")
    void adjustsOnlyBarsBeforeSplit() {
        writer.write(stockId, List.of(
                bar("2018-05-02", "2500000"),
                bar("2018-05-03", "2650000"),
                bar("2018-05-04", "53000")));   // 분할 당일 — 이미 조정된 가격

        recalculator.apply(stockId, split("2018-05-04", "50"));

        // 분할 이전: adj_close = close / 50
        assertThat(adjClose("2018-05-02")).isEqualByComparingTo("50000");
        assertThat(adjClose("2018-05-03")).isEqualByComparingTo("53000");
        assertThat(adjFactor("2018-05-02")).isEqualByComparingTo("0.02");
        // 분할 당일 이후는 그대로다.
        assertThat(adjClose("2018-05-04")).isEqualByComparingTo("53000");
        assertThat(adjFactor("2018-05-04")).isEqualByComparingTo("1");
        // 원주가는 보존된다 — PER·시가총액이 이것을 쓴다.
        assertThat(close("2018-05-02")).isEqualByComparingTo("2500000");
    }

    @Test
    @DisplayName("분할이 두 번 있으면 계수가 누적된다 — 덮어쓰면 앞선 분할이 사라진다")
    void accumulatesMultipleSplits() {
        writer.write(stockId, List.of(bar("2020-01-02", "1000")));

        recalculator.apply(stockId, split("2021-01-04", "2"));
        recalculator.apply(stockId, split("2022-01-03", "5"));

        // 1/2 × 1/5 = 1/10
        assertThat(adjFactor("2020-01-02")).isEqualByComparingTo("0.1");
        assertThat(adjClose("2020-01-02")).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("액면병합은 과거 가격을 올린다 — 방향을 뒤집으면 아무도 눈치채지 못한다")
    void handlesReverseSplit() {
        writer.write(stockId, List.of(bar("2026-01-02", "100")));

        // 10주를 1주로 합치면 ratio = 0.1이고 과거 가격은 10배가 된다.
        recalculator.apply(stockId, new CorporateAction(
                LocalDate.parse("2026-02-02"), CorporateAction.Type.REVERSE_SPLIT,
                new BigDecimal("0.1"), CorporateAction.DetectionSource.SOURCE_DISCLOSURE));

        assertThat(adjClose("2026-01-02")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("휴리스틱으로 탐지한 사건은 자동 적용을 거부한다 — 오탐의 대가가 너무 크다")
    void refusesHeuristicDetection() {
        writer.write(stockId, List.of(bar("2026-01-02", "1000")));

        assertThatThrownBy(() -> recalculator.apply(stockId, new CorporateAction(
                LocalDate.parse("2026-02-02"), CorporateAction.Type.SPLIT,
                new BigDecimal("2"), CorporateAction.DetectionSource.HEURISTIC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("휴리스틱");

        assertThat(adjClose("2026-01-02")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("시세 급변을 분할 후보로 탐지하되 HEURISTIC으로 표시한다")
    void detectsCandidatesAsHeuristic() {
        writer.write(stockId, List.of(
                bar("2026-01-02", "100000"),
                bar("2026-01-05", "2000"),      // 1/50로 급락 — 분할 의심
                bar("2026-01-06", "2050")));

        List<CorporateAction> candidates = recalculator.detectCandidates(stockId);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).effectiveDate()).isEqualTo(LocalDate.parse("2026-01-05"));
        assertThat(candidates.get(0).type()).isEqualTo(CorporateAction.Type.SPLIT);
        assertThat(candidates.get(0).ratio()).isEqualByComparingTo("50");
        assertThat(candidates.get(0).source())
                .isEqualTo(CorporateAction.DetectionSource.HEURISTIC);
    }

    @Test
    @DisplayName("정상적인 등락은 후보로 잡지 않는다")
    void ignoresNormalPriceMovement() {
        writer.write(stockId, List.of(
                bar("2026-01-02", "10000"),
                bar("2026-01-05", "10500"),
                bar("2026-01-06", "9800")));

        assertThat(recalculator.detectCandidates(stockId)).isEmpty();
    }

    @Test
    @DisplayName("조정 대상 시세가 없으면 0을 반환한다 — 상장 이전 분할이 있을 수 있다")
    void returnsZeroWhenNothingToAdjust() {
        writer.write(stockId, List.of(bar("2026-03-02", "1000")));

        assertThat(recalculator.apply(stockId, split("2026-01-04", "2"))).isZero();
    }

    private BigDecimal adjClose(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT adj_close FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    private BigDecimal adjFactor(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT adj_factor FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    private BigDecimal close(String date) {
        return jdbcTemplate.queryForObject(
                "SELECT close FROM price_daily WHERE stock_id = ? AND trade_date = ?",
                BigDecimal.class, stockId, LocalDate.parse(date));
    }

    private static CorporateAction split(String date, String ratio) {
        return new CorporateAction(LocalDate.parse(date), CorporateAction.Type.SPLIT,
                new BigDecimal(ratio), CorporateAction.DetectionSource.SOURCE_DISCLOSURE);
    }

    /** OHLC를 종가 기준으로 만든 봉. 분할 계산은 종가만 쓰므로 나머지는 종가에 맞춘다. */
    private static RawPriceBar bar(String date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new RawPriceBar(LocalDate.parse(date), c, c, c, c, 1_000_000L, null, null, false);
    }
}
