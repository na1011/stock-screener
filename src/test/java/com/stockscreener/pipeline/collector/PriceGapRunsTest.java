package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 시세 구멍 구간의 정의를 픽스처로 고정한다({@link StockRegistrar#priceGapRuns}).
 *
 * <p>달력은 평일 30거래일({@code D(1)}~{@code D(30)}), 지표 보존 창은 {@code D(15)}~{@code D(29)},
 * 대상일 {@code D(30)}, 일일 갱신 창의 시작 {@code D(27)}, 읽기 창 20봉이라 하한이 {@code D(11)}이다.
 *
 * <p>운영에서 이 정의가 잡은 것: 재편입 종목의 편출 동안 봉 · 연휴 중 편출된 종목의 마지막 편입일 봉 ·
 * CUSIP 전환으로 소스가 짧게 준 종목(TEVA)의 편입일 봉(2026-10-05 · 24구간). 안 잡아야 하는 것:
 * 신규 상장의 첫 봉 앞, 소스가 재사용 티커로 다른 증권 봉을 주는 첫 봉 앞(FIG형).
 */
@JpaSliceTest
@Import({StockRegistrar.class, DataSourceRegistry.class})
class PriceGapRunsTest {

    private static final int READ_BARS = 20;

    @Autowired
    private StockRegistrar registrar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<LocalDate> days = tradingDays(LocalDate.of(2026, 1, 5), 30);

    @BeforeEach
    void calendar() {
        jdbcTemplate.update("DELETE FROM indicator_trade_date WHERE market_code = 'KOSPI'");
        for (int i = 15; i <= 29; i++) {
            jdbcTemplate.update("INSERT INTO indicator_trade_date (market_code, trade_date) VALUES ('KOSPI', ?)",
                    d(i));
        }
        for (int i = 1; i <= 30; i++) {
            jdbcTemplate.update("INSERT INTO market_index_daily (index_code, trade_date, close) VALUES ('KOSPI', ?, 100)",
                    d(i));
        }
    }

    @Test
    @DisplayName("IMPORTANT — 재편입 종목의 창 앞 구멍을 한 구간으로 낸다 — 창 안은 일일 갱신의 창 콜이 받는다")
    void readmittedStockGetsThePreWindowHole() {
        long id = stock("900001");
        membership(id, d(12), d(18));
        membership(id, d(25), null);
        bars(id, 1, 20);
        bars(id, 27, 30);

        assertThat(runs()).extracting(PriceGapRun::stockId, PriceGapRun::currentMember,
                        PriceGapRun::from, PriceGapRun::to, PriceGapRun::days)
                .containsExactly(tuple(id, true, d(21), d(26), 6));
    }

    @Test
    @DisplayName("구멍 없는 지금 편입은 구간이 없다 — 창 콜만 나간다")
    void completeStockHasNoRun() {
        long id = stock("900002");
        membership(id, d(1), null);
        bars(id, 1, 30);

        assertThat(runs()).isEmpty();
    }

    @Test
    @DisplayName("IMPORTANT — 보존 창 안에서 편출된 종목의 마지막 편입일 구멍을 낸다 — 15일 창에 묶이지 않는다")
    void departedStockGetsItsLastMemberDay() {
        long id = stock("900003");
        membership(id, d(16), d(24));
        bars(id, 1, 22);

        assertThat(runs()).extracting(PriceGapRun::stockId, PriceGapRun::currentMember,
                        PriceGapRun::from, PriceGapRun::to)
                .containsExactly(tuple(id, false, d(23), d(23)));
    }

    @Test
    @DisplayName("IMPORTANT — 첫 봉 앞에 편입일이 있으면 읽기 창 하한부터 묻는다(TEVA형 — 소스가 짧게 준 것)")
    void memberBeforeFirstBarIsAskedFromTheReadFloor() {
        long id = stock("900004");
        membership(id, d(16), null);
        bars(id, 26, 30);

        assertThat(runs()).extracting(PriceGapRun::from, PriceGapRun::to, PriceGapRun::days)
                .containsExactly(tuple(d(11), d(25), 15));
    }

    @Test
    @DisplayName("IMPORTANT — 첫 봉 앞에 편입일이 없으면 첫 봉 앞을 안 묻는다 — 소스가 재사용 티커로 다른 증권 봉을 준다(FIG형)")
    void firstBarBeforeMembershipIsNotAskedBefore() {
        long id = stock("900005");
        membership(id, d(20), null);
        bars(id, 18, 30);

        assertThat(runs()).isEmpty();
    }

    @Test
    @DisplayName("지수도 없고 지금 편입 절반 미만만 봉을 가진 날은 거래일이 아니다 — 토요일 꼬리의 몇 종목 봉")
    void dayWithoutIndexAndWithFewBarsIsNotATradingDay() {
        jdbcTemplate.update("DELETE FROM market_index_daily WHERE index_code = 'KOSPI' AND trade_date = ?", d(22));
        long holed = stock("900006");
        membership(holed, d(1), null);
        bars(holed, 1, 21);
        bars(holed, 23, 30);
        long full = stock("900007");
        membership(full, d(1), null);
        bars(full, 1, 21);
        bars(full, 23, 30);
        long third = stock("900008");
        membership(third, d(1), null);
        bars(third, 1, 30);

        // 셋 중 하나만 D(22) 봉을 가져 절반 미만 — 지수도 없으니 거래일이 아니다.
        assertThat(runs()).isEmpty();
    }

    @Test
    @DisplayName("지수만 빠진 날도 지금 편입 절반 이상이 봉을 가졌으면 거래일이다 — 지수 수집 실패가 구멍을 숨기지 않는다")
    void dayWithBarsButNoIndexIsStillATradingDay() {
        jdbcTemplate.update("DELETE FROM market_index_daily WHERE index_code = 'KOSPI' AND trade_date = ?", d(22));
        long holed = stock("900009");
        membership(holed, d(1), null);
        bars(holed, 1, 21);
        bars(holed, 23, 30);
        long full = stock("900010");
        membership(full, d(1), null);
        bars(full, 1, 30);

        assertThat(runs()).extracting(PriceGapRun::stockId, PriceGapRun::from, PriceGapRun::to)
                .containsExactly(tuple(holed, d(22), d(22)));
    }

    @Test
    @DisplayName("창 시작일의 구멍은 구간에 안 든다 — 창 콜이 받는다 · 하루 앞은 든다")
    void windowStartBoundary() {
        long id = stock("900011");
        membership(id, d(1), null);
        bars(id, 1, 25);
        bars(id, 28, 30);

        assertThat(runs()).extracting(PriceGapRun::from, PriceGapRun::to)
                .containsExactly(tuple(d(26), d(26)));
    }

    @Test
    @DisplayName("한 종목의 떨어진 두 구멍은 두 구간이다 — 한 콜로 묶으면 사이의 봉을 매일 다시 받는다")
    void separatedHolesAreSeparateRuns() {
        long id = stock("900012");
        membership(id, d(1), null);
        bars(id, 1, 12);
        bars(id, 14, 19);
        bars(id, 21, 30);

        assertThat(runs()).extracting(PriceGapRun::from, PriceGapRun::to)
                .containsExactly(tuple(d(13), d(13)), tuple(d(20), d(20)));
    }

    @Test
    @DisplayName("읽기 창 하한 앞의 구멍은 안 묻는다 — 지표가 안 읽는다")
    void holeBeforeTheReadFloorIsIgnored() {
        long id = stock("900013");
        membership(id, d(1), null);
        bars(id, 1, 9);
        bars(id, 11, 30);

        assertThat(runs()).isEmpty();
    }

    @Test
    @DisplayName("봉이 한 개도 없는 편입은 여기서 안 낸다 — 편입 결손 꼬리(PRICE_HISTORY)가 7년을 받는다")
    void stockWithoutBarsIsLeftToTheGapFiller() {
        long id = stock("900014");
        membership(id, d(1), null);

        assertThat(runs()).isEmpty();
    }

    private List<PriceGapRun> runs() {
        return registrar.priceGapRuns(d(30), d(27), READ_BARS).stream()
                .filter(run -> run.ticker().startsWith("9000") || run.ticker().startsWith("9001"))
                .toList();
    }

    private LocalDate d(int i) {
        return days.get(i - 1);
    }

    private long stock(String ticker) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, is_active) VALUES ('KOSPI', ?, ?, true) RETURNING id
                """, Long.class, ticker, "구멍 " + ticker);
    }

    private void membership(long stockId, LocalDate from, LocalDate to) {
        jdbcTemplate.update("""
                INSERT INTO universe_membership (universe_code, stock_id, effective_from, effective_to)
                VALUES ('KOSPI200', ?, ?, ?)
                """, stockId, from, to);
    }

    private void bars(long stockId, int fromDay, int toDay) {
        for (int i = fromDay; i <= toDay; i++) {
            jdbcTemplate.update("""
                    INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume)
                    VALUES (?, ?, 100, 100, 100, 100, 100, 1)
                    """, stockId, d(i));
        }
    }

    private static List<LocalDate> tradingDays(LocalDate start, int count) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate day = start;
        while (result.size() < count) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                result.add(day);
            }
            day = day.plusDays(1);
        }
        return result;
    }
}
