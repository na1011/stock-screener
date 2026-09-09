package com.stockscreener.probe.backtest;

import com.stockscreener.indicator.IndicatorDataReader.FinancialVisibility;
import com.stockscreener.indicator.IndicatorRunner;
import com.stockscreener.support.ReadOnlyDatabaseGuard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장 값과 시점 재계산이 갈리는 <b>날짜 경계</b>를 한 종목에서 찾는다.
 *
 * <p>{@link StoredAsOfParityProbeTest}가 「갈린다」까지 냈고, 종목×날짜 격자로 세니 재무 지표가
 * <b>옛 180거래일 창의 시작에서 계단</b>을 이룬다. 그 계단이 <b>계산이 거기서 멎는 것</b>인지
 * <b>옛 실행의 값이 안 덮인 것</b>인지를 가르려면 날짜를 하나씩 밟아야 한다.
 *
 * <p><b>왜 한 종목인가.</b> {@code computeAt} 한 번이 종목당 수십 ms라 날짜를 촘촘히 밟을 수
 * 있다. 전 종목으로 넓히면 같은 물음에 몇 시간이 든다.
 */
@Tag("backtest")
@SpringBootTest(classes = BacktestHarnessContext.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("backtest")
@Import(ReadOnlyDatabaseGuard.class)
class StoredCoverageBoundaryProbeTest {

    /** 볼 종목. {@code -Dsweep.tickers}가 아니라 여기 박는다 — 진단용이고 대상이 바뀌면 고친다. */
    private static final List<String> TICKERS = List.of("WAT", "ANET", "UI");

    private static final List<String> CODES =
            List.of("GROSS_MARGIN", "PROFIT_STREAK_Y", "ROE_MIN_3Y", "MARKET_CAP", "PER");

    @Autowired
    private IndicatorRunner runner;

    @Autowired
    private AsOfCalendar calendar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("재무 지표가 저장에는 있고 재계산에는 없는 날짜 경계를 찾는다")
    void findBoundaryWhereRecomputationStops() {
        List<LocalDate> dates = calendar.everyTradingDay(
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 9, 8), java.util.Set.of("US"));
        assertThat(dates).as("US 거래일을 하나도 못 읽었다").isNotEmpty();

        for (String ticker : TICKERS) {
            Long stockId = jdbcTemplate.queryForObject(
                    "SELECT id FROM stock WHERE ticker = ? AND market_code = 'US'",
                    Long.class, ticker);
            System.out.printf("%n===== %s (id=%d) · 거래일 %d개 · 20일 간격 =====%n",
                    ticker, stockId, dates.size());
            System.out.printf("%-12s %-10s %s%n", "날짜", "출처", String.join(" ", CODES));
            for (int i = 0; i < dates.size(); i += 20) {
                LocalDate asOf = dates.get(i);
                Map<String, NavigableMap<LocalDate, BigDecimal>> indexes =
                        runner.marketIndexesFor(asOf);
                Map<String, BigDecimal> mine =
                        runner.computeAt(stockId, asOf, indexes, FinancialVisibility.KNOWN_ON);
                // 대조군. 저장본이 이쪽과 맞으면 그 행은 ADR-0128 이전의 잔재다 —
                // 「계산이 멎었다」가 아니라 「옛 판이 안 덮였다」가 답이 된다.
                Map<String, BigDecimal> lookAhead =
                        runner.computeAt(stockId, asOf, indexes, FinancialVisibility.LATEST);
                Map<String, BigDecimal> stored = storedAt(stockId, asOf);
                System.out.printf("%-12s %-10s %s%n", asOf, "KNOWN_ON", mark(mine));
                System.out.printf("%-12s %-10s %s%n", "", "LATEST", mark(lookAhead));
                System.out.printf("%-12s %-10s %s   %s%n", "", "저장", mark(stored),
                        verdict(stored, mine, lookAhead));
            }
        }
    }

    /**
     * 저장본이 두 대조군 중 <b>어느 쪽과 맞는가</b>.
     *
     * <p>이것이 이 탐침의 답이다 — {@code KNOWN_ON}과 맞으면 저장본이 시점 재현이고,
     * {@code LATEST}와만 맞으면 그 행은 <b>미래를 알고 계산된 값이 안 덮인 채 남은 것</b>이다.
     */
    private static String verdict(Map<String, BigDecimal> stored,
                                  Map<String, BigDecimal> knownOn,
                                  Map<String, BigDecimal> latest) {
        int matchesKnownOn = 0;
        int matchesLatestOnly = 0;
        int neither = 0;
        for (String code : CODES) {
            BigDecimal s = stored.get(code);
            if (s == null) {
                continue;
            }
            BigDecimal k = knownOn.get(code);
            BigDecimal l = latest.get(code);
            if (k != null && k.compareTo(s) == 0) {
                matchesKnownOn++;
            } else if (l != null && l.compareTo(s) == 0) {
                matchesLatestOnly++;
                System.out.printf("        ! %s 저장=%s KNOWN_ON=%s LATEST=%s%n", code, s, k, l);
            } else {
                neither++;
                System.out.printf("        ? %s 저장=%s KNOWN_ON=%s LATEST=%s%n", code, s, k, l);
            }
        }
        return "저장 일치: KNOWN_ON " + matchesKnownOn
                + " · LATEST만 " + matchesLatestOnly + " · 어느 쪽도 아님 " + neither;
    }

    /** 코드마다 값이 있으면 {@code O}, 없으면 {@code .} — 어느 코드에서 멎는지만 보면 된다. */
    private static String mark(Map<String, BigDecimal> values) {
        StringBuilder sb = new StringBuilder();
        for (String code : CODES) {
            sb.append(values.get(code) == null ? " ." : " O");
            sb.append(" ".repeat(Math.max(0, code.length() - 2)));
        }
        return sb.toString();
    }

    private Map<String, BigDecimal> storedAt(long stockId, LocalDate asOf) {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT indicator_code, value FROM indicator_value
                 WHERE stock_id = ? AND trade_date = ? AND indicator_code = ANY (?)
                """,
                ps -> {
                    ps.setLong(1, stockId);
                    ps.setObject(2, asOf);
                    ps.setArray(3, ps.getConnection()
                            .createArrayOf("varchar", CODES.toArray()));
                },
                rs -> {
                    out.put(rs.getString(1), rs.getBigDecimal(2));
                });
        return out;
    }
}
