package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.MarketIndexDataSource;
import com.stockscreener.pipeline.datasource.RawIndexBar;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 지수 적재와 <b>결손을 무엇으로 판정하는가</b>.
 *
 * <p>후자가 이 테스트의 요점이다. 수집이 15일 창으로 도는 이상 "이 실행이 쓴 행 수"로 물으면
 * 대상일이 비어 있어도 창 안 과거 날짜의 재적재가 그 수를 채워 <b>알림이 원리적으로 발화하지
 * 않는다.</b> 그러면 지수를 쓰는 지표들({@code EXCESS_RETURN_26W}·{@code EXCESS_RETURN_52W}·
 * {@code BETA_104W})이 매일 전 종목 결손인데 배치는 초록이다. <b>지수 소비자가 늘수록 이 침묵이 비싸진다</b> —
 * 프리셋 셋이 52주 초과수익률의 백분위를 조건으로 쓴다.
 */
@JpaSliceTest
class MarketIndexCollectorTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 11);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("받은 봉을 적재하고, 같은 구간을 다시 받으면 0행이다")
    void upsertIsIdempotent() throws Exception {
        MarketIndexCollector collector = collectorWith(
                new StubIndexSource(List.of(bar(D1, "2770.69"), bar(D2, "2801.10"))));

        assertThat(collector.collect("KOSPI", D1, D2)).isEqualTo(2);
        // 창으로 도는 수집이 같은 행을 평일마다 11~12번 다시 본다. 그때 0이어야 한다 —
        // IS DISTINCT FROM 가드를 지우면 여기가 2로 바뀐다.
        assertThat(collector.collect("KOSPI", D1, D2)).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT close FROM market_index_daily WHERE index_code = 'KOSPI' AND trade_date = ?",
                BigDecimal.class, D2)).isEqualByComparingTo("2801.10");
    }

    @Test
    @DisplayName("종가가 0 이하인 봉은 버린다 — 저장되면 26주 수익률의 분모가 되어 조용히 0%가 된다")
    void dropsNonPositiveClose() throws Exception {
        MarketIndexCollector collector = collectorWith(
                new StubIndexSource(List.of(bar(D1, "0"), bar(D2, "2801.10"))));

        assertThat(collector.collect("KOSDAQ", D1, D2)).isEqualTo(1);
        assertThat(countOn("KOSDAQ", D1)).isZero();
        assertThat(countOn("KOSDAQ", D2)).isEqualTo(1);
    }

    @Test
    @DisplayName("CHECK가 모르는 지수 코드를 막는다 — 리더와 갈리면 0행이 '지수 결손'으로 접힌다")
    void rejectsUnknownIndexCode() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO market_index_daily (index_code, trade_date, close)
                VALUES ('코스피', ?, 2770.69)
                """, D1))
                .hasMessageContaining("ck_market_index_code");
    }

    @Test
    @DisplayName("결손 판정은 쓴 행 수가 아니라 대상일 행 수로 한다 — 창이 과거를 채워도 답이 같다")
    void calendarAnswersTargetDateNotWrittenRows() throws Exception {
        MarketIndexCalendar calendar = new MarketIndexCalendar(jdbcTemplate);
        // 창 안의 과거 하루만 들어오고 대상일(D2)은 비었다. 쓴 행 수는 1이라 "받았다"로 보인다.
        MarketIndexCollector collector =
                collectorWith(new StubIndexSource(List.of(bar(D1, "2770.69"))));
        assertThat(collector.collect("KOSPI", D1, D2)).isEqualTo(1);

        assertThat(calendar.countOn("KOSPI", D2))
                .as("쓴 행 수는 1이지만 대상일은 비어 있다. 이 값이 0이어야 알림이 발화한다")
                .isZero();
        assertThat(calendar.countOn("KOSPI", D1)).isEqualTo(1);
        assertThat(calendar.latestTradeDate("KOSPI")).contains(D1);
    }

    private long countOn(String code, LocalDate date) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM market_index_daily WHERE index_code = ? AND trade_date = ?",
                Long.class, code, date);
    }

    private MarketIndexCollector collectorWith(MarketIndexDataSource source) {
        DataSourceRegistry registry = new DataSourceRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(source),
                List.of(),
                List.of());
        return new MarketIndexCollector(registry, jdbcTemplate);
    }

    private static RawIndexBar bar(LocalDate date, String close) {
        return new RawIndexBar(date, new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), 100L, new BigDecimal("1000"), 200);
    }

    private record StubIndexSource(List<RawIndexBar> bars) implements MarketIndexDataSource {

        @Override
        public Set<String> supportedIndexCodes() {
            return Set.of("KOSPI", "KOSDAQ");
        }

        @Override
        public String sourceName() {
            return "stub";
        }

        @Override
        public List<RawIndexBar> fetchDailyBars(String indexCode, LocalDate from, LocalDate to)
                throws DataSourceException {
            return bars;
        }
    }
}
