package com.stockscreener.pipeline.collector;

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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BarGapReader}가 <b>무엇을 결측이라 부르고 무엇을 안 부르는가</b>를 잠근다.
 *
 * <p>가장 중요한 것이 판정 구간의 좁힘이다 — 안 좁히면 첫 실행이 오탐만 내고, <b>오탐만 내는
 * 판정기는 안 만든 것과 같다.</b> 읽기만 하므로 {@code @Transactional} 롤백으로 격리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseGuard.class)
@Transactional
class BarGapReaderTest {

    @Autowired
    private BarGapReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCalendar() {
        // 달력: 2024-01-02 ~ 01-05 나흘이 세션이고 01-03은 휴장이다.
        for (String date : new String[] {"2024-01-02", "2024-01-04", "2024-01-05", "2024-01-08"}) {
            jdbcTemplate.update("""
                    INSERT INTO market_calendar (exchange_code, session_date, core_start, core_end)
                    VALUES ('NYSE', ?::date, ?::date + time '09:30', ?::date + time '16:00')
                    """, date, date, date);
        }
    }

    @Test
    @DisplayName("세션인데 봉이 없으면 잡고, 휴장은 안 잡는다")
    void findsSessionsWithoutBars() {
        long stockId = usStock("AAA", "NYSE");
        bars(stockId, "2024-01-02", "2024-01-08");   // 01-04·01-05가 비었다

        BarGapReader.Report report = reader.read(List.of("AAA"), null);

        assertThat(report.gaps()).extracting(BarGapReader.Gap::date)
                .as("01-03은 휴장이라 안 잡혀야 한다")
                .containsExactlyInAnyOrder(LocalDate.of(2024, 1, 4), LocalDate.of(2024, 1, 5));
        assertThat(report.count(BarGapReader.Verdict.SESSION_WITHOUT_BAR)).isEqualTo(2);
        assertThat(report.stocksInScope()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 봉 이후의 세션은 결측이 아니다 — 안 좁히면 첫 실행이 오탐만 낸다")
    void clampsToTheStocksOwnBarRange() {
        long stockId = usStock("BBB", "NYSE");
        bars(stockId, "2024-01-02", "2024-01-04");   // 01-05·01-08은 이 종목의 창 밖이다

        BarGapReader.Report report = reader.read(List.of("BBB"), null);

        assertThat(report.gaps())
                .as("01-05·01-08은 마지막 봉보다 뒤라 판정 구간에 안 든다")
                .isEmpty();
        assertThat(report.stocksInScope()).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 봉 이전의 세션도 결측이 아니다 — 상장 전을 결측으로 부르지 않는다")
    void clampsToTheStocksFirstBar() {
        long stockId = usStock("CCC", "NYSE");
        bars(stockId, "2024-01-05", "2024-01-08");   // 01-02·01-04는 첫 봉보다 앞이다

        assertThat(reader.read(List.of("CCC"), null).gaps()).isEmpty();
    }

    @Test
    @DisplayName("비세션인데 봉이 있으면 잡는다 — 달력이 세션을 빠뜨린 유일한 신호다")
    void findsBarsOnNonSessions() {
        long stockId = usStock("DDD", "NYSE");
        bars(stockId, "2024-01-02", "2024-01-03", "2024-01-04", "2024-01-05", "2024-01-08");

        BarGapReader.Report report = reader.read(List.of("DDD"), null);

        assertThat(report.gaps())
                .as("01-03은 달력에 없는데 봉이 있다")
                .singleElement()
                .satisfies(gap -> {
                    assertThat(gap.verdict()).isEqualTo(BarGapReader.Verdict.BAR_ON_NON_SESSION);
                    assertThat(gap.date()).isEqualTo(LocalDate.of(2024, 1, 3));
                });
    }

    @Test
    @DisplayName("--from은 판정 구간을 더 좁힐 뿐 넓히지 않는다")
    void fromNarrowsTheWindow() {
        long stockId = usStock("EEE", "NYSE");
        bars(stockId, "2024-01-02", "2024-01-08");

        assertThat(reader.read(List.of("EEE"), LocalDate.of(2024, 1, 5)).gaps())
                .extracting(BarGapReader.Gap::date)
                .containsExactly(LocalDate.of(2024, 1, 5));
    }

    @Test
    @DisplayName("국내 종목은 달력에 원리적으로 안 걸린다 — listing_exchange가 NULL이라 조인이 없다")
    void domesticStocksAreNeverJudged() {
        Long domestic = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name) VALUES ('KOSPI', '900001', '판정대상아님')
                RETURNING id
                """, Long.class);
        bars(domestic, "2024-01-02", "2024-01-04");

        BarGapReader.Report report = reader.read(List.of("900001"), null);

        assertThat(report.stocksInScope())
                .as("조인 키가 NULL이라 판정 구간 자체가 안 잡힌다")
                .isZero();
        assertThat(report.gaps()).isEmpty();
        assertThat(report.stocksWithoutCalendar())
                .as("판정불가로 세어져야 한다 — 조용히 사라지면 안 본 것과 같다")
                .isPositive();
    }

    @Test
    @DisplayName("봉이 한 행도 없는 종목은 결측이 아니라 미적재이고, 그래도 수로 신고한다")
    void countsStocksWithNoBarsSeparately() {
        usStock("FFF", "NYSE");   // 봉을 안 넣는다

        BarGapReader.Report report = reader.read(List.of("FFF"), null);

        assertThat(report.gaps()).isEmpty();
        assertThat(report.stocksInScope()).isZero();
        assertThat(report.stocksWithoutBars()).isPositive();
    }

    // ── 도구 ─────────────────────────────────────────────────────────────

    private long usStock(String ticker, String exchange) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO stock (market_code, ticker, name, listing_exchange)
                VALUES ('US', ?, ?, ?)
                RETURNING id
                """, Long.class, ticker, ticker + " Inc.", exchange);
        return id == null ? 0L : id;
    }

    private void bars(long stockId, String... dates) {
        for (String date : dates) {
            jdbcTemplate.update("""
                    INSERT INTO price_daily
                        (stock_id, trade_date, open, high, low, close, adj_close, adj_factor, volume)
                    VALUES (?, ?::date, 100, 100, 100, 100, 100, 1, 1000)
                    """, stockId, date);
        }
    }
}
