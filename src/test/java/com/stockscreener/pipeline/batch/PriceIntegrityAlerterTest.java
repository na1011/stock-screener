package com.stockscreener.pipeline.batch;

import com.stockscreener.notification.AlertEvent;
import com.stockscreener.notification.AlertPort;
import com.stockscreener.pipeline.collector.PriceObservationWindow;
import com.stockscreener.support.JpaSliceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시세 원장 무결성 감시(ADR-0181). 꼴마다 적중과 대조군 · 면제 · 7일 억제.
 * 달력은 지수 봉(US)이 있는 날이다 — 시험은 그날들을 {@code market_index_daily}에 심는다.
 */
@JpaSliceTest
@Import(PriceObservationWindow.class)
class PriceIntegrityAlerterTest {

    private static final LocalDate START = LocalDate.of(2025, 3, 3);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PriceObservationWindow window;

    private final List<AlertEvent> sent = new ArrayList<>();
    private final AlertPort port = sent::add;

    @BeforeEach
    void calendar() {
        jdbc.update("INSERT INTO indicator_trade_date (market_code, trade_date) VALUES ('US', DATE '2025-09-01') ON CONFLICT DO NOTHING");
        for (LocalDate d = START; d.isBefore(START.plusDays(60)); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                jdbc.update("INSERT INTO market_index_daily (index_code, trade_date, close) VALUES ('US', ?, 100) ON CONFLICT DO NOTHING", d);
            }
        }
    }

    @Test
    @DisplayName("ⓒ 이웃 봉 간격 > 5 시장일을 낸다 · 4시장일 공백(대조군)은 안 낸다")
    void gapsOverFiveMarketDays() {
        long gap = member("GAPX");
        traded(gap, START, 3);
        traded(gap, marketDay(10), 3);
        long near = member("NEAR");
        traded(near, START, 3);
        traded(near, marketDay(7), 3);

        assertThat(alerter(Set.of()).evaluate()).filteredOn(h -> h.check().equals("GAP")).extracting(PriceIntegrityAlerter.Hit::ticker)
                .containsExactly("GAPX");
    }

    @Test
    @DisplayName("ⓓ 5봉 이상 중간 평봉 구간이 외따로 있으면 낸다 · 면제 파일의 (검사, 표지, 시작일)은 뺀다")
    void lonelyFlatRunAndExemption() {
        long vg = member("VGX");
        traded(vg, START, 2);
        flat(vg, marketDay(2), 6);
        traded(vg, marketDay(8), 2);

        assertThat(alerter(Set.of()).evaluate()).extracting(PriceIntegrityAlerter.Hit::check).contains("FLAT_RUN");
        assertThat(alerter(Set.of("FLAT_RUN\tVGX\t" + marketDay(2))).evaluate()).extracting(PriceIntegrityAlerter.Hit::check)
                .doesNotContain("FLAT_RUN");
    }

    @Test
    @DisplayName("ⓑ·ⓐ 머리 평봉(술어 밖 저장 봉)을 낸다 · 같은 (검사, 행)은 7일 안에 다시 안 보낸다")
    void headFlatAndSuppression() {
        long sn = member("SNH");
        flat(sn, START, 3);
        traded(sn, marketDay(3), 3);
        PriceIntegrityAlerter alerter = alerter(Set.of());

        assertThat(alerter.evaluate()).extracting(PriceIntegrityAlerter.Hit::check).contains("OUTSIDE_WINDOW");
        assertThat(alerter.evaluateAndAlert()).isTrue();
        assertThat(alerter.evaluateAndAlert()).as("7일 억제").isFalse();
        assertThat(sent).singleElement().satisfies(e -> assertThat(e.body()).contains("SNH"));
    }

    @Test
    @DisplayName("ⓔ 원장 없는 큰 배수를 표지 주식수가 상쇄하면 낸다(UHAL 꼴) · 주식수가 그대로면(실제 변동 · 대조군) 안 낸다")
    void offsetJump() {
        long uhal = member("UHALX");
        bars(uhal, START, 5, "513.47");
        bars(uhal, marketDay(5), 5, "52.02");
        shares(uhal, LocalDate.of(2025, 2, 28), LocalDate.of(2025, 3, 1), "19607788");
        shares(uhal, LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 30), "196077880");
        long real = member("KODX");
        bars(real, START, 5, "50.35");
        bars(real, marketDay(5), 5, "9.84");
        shares(real, LocalDate.of(2025, 2, 28), LocalDate.of(2025, 3, 1), "51934500");
        shares(real, LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 30), "51953631");

        assertThat(alerter(Set.of()).evaluate()).filteredOn(h -> h.check().equals("OFFSET_JUMP"))
                .extracting(PriceIntegrityAlerter.Hit::ticker).containsExactly("UHALX");
    }

    @Test
    @DisplayName("실제 면제 파일 — 열 넷 · 근거 필수로 읽힌다")
    void realExemptFileLoads() {
        assertThat(PriceIntegrityAlerter.loadExempt(PriceIntegrityAlerter.EXEMPT_FILE)).contains("FLAT_RUN\tCORZ\t2023-01-03");
    }

    private PriceIntegrityAlerter alerter(Set<String> exempt) {
        return new PriceIntegrityAlerter(jdbc, window, port, Clock.fixed(Instant.parse("2026-10-19T06:30:00Z"), ZoneOffset.UTC), exempt);
    }

    private long member(String ticker) {
        long id = jdbc.queryForObject("INSERT INTO stock (market_code, ticker, name) VALUES ('US', ?, ?) RETURNING id", Long.class, ticker, ticker);
        jdbc.update("INSERT INTO universe_membership (universe_code, stock_id, effective_from) VALUES ('US_TOP1000', ?, DATE '2025-09-01')", id);
        return id;
    }

    private LocalDate marketDay(int n) {
        LocalDate d = START;
        int i = 0;
        while (i < n) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                i++;
            }
        }
        return d;
    }

    private void traded(long id, LocalDate from, int days) {
        bars(id, from, days, "10");
    }

    private void bars(long id, LocalDate from, int days, String close) {
        insert(id, from, days, close, 1_000);
    }

    private void flat(long id, LocalDate from, int days) {
        insert(id, from, days, "10", 0);
    }

    private void insert(long id, LocalDate from, int days, String close, long volume) {
        BigDecimal c = new BigDecimal(close);
        LocalDate d = from;
        int i = 0;
        while (i < days) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                jdbc.update("""
                        INSERT INTO price_daily (stock_id, trade_date, open, high, low, close, adj_close, volume)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, d, c, c, c, c, c, volume);
                i++;
            }
            d = d.plusDays(1);
        }
    }

    private void shares(long id, LocalDate periodEnd, LocalDate published, String shares) {
        jdbc.update("""
                INSERT INTO financial_statement (stock_id, fiscal_year, fiscal_period, period_start, period_end, published_at, revision_no,
                                                 is_latest, currency_code, shares_outstanding)
                VALUES (?, ?, ?, ?, ?, ?, 0, true, 'USD', ?)""", id, periodEnd.getYear(), periodEnd.getMonthValue() <= 2 ? "FY" : "Q1",
                periodEnd.minusMonths(3), periodEnd, published, new BigDecimal(shares));
    }
}
