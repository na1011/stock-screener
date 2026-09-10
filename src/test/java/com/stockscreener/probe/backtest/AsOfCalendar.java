package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * as-of를 <b>실재하는 거래일로만</b> 고른다(P17, ADR-0071).
 *
 * <p>휴장일을 as-of로 주면 {@code IndicatorRunner.computeAt}이 전 종목 빈 맵을 낸다 —
 * 마지막 봉의 일자가 {@code asOf}와 정확히 같을 때만 값을 내기 때문이다. 그러면 필수 조건이
 * 전부 판정불가가 되어 <b>통과가 0</b>이 되는데, 어떤 에러도 나지 않아 <b>게이트 탓으로
 * 읽기 쉬운 0</b>이 된다. 달력으로 날짜를 만들지 않는 이유가 그것이다.
 *
 * <p>거래일의 정본은 {@code price_daily}다. 휴장일 목록을 코드로 들고 있지 않고, 들면
 * 소스와 갈릴 때 <b>갈렸다는 사실이 통과 0으로만 드러난다.</b>
 * {@code market_calendar}도 쓰지 않는다 — {@code exchange_code}가 NYSE·NASDAQ뿐이라
 * 국내가 아예 없다(ADR-0091).
 *
 * <p><b>세 메서드가 전부 시장을 받는다. 기본값을 주지 않는다</b>(ADR-0108) —
 * {@code AsOfResolver.resolve}가 같은 이유로 그렇다(ADR-0095). 기본값이 있으면 시장을 안 고른
 * 호출부가 <b>아무 증상 없이</b> 국내 휴장·미국 개장일을 as-of로 집어 국내를 통째로 비운다.
 * 실측으로 미국 전용 거래일이 <b>176일</b>이고, 분기 격자 18점 중 <b>6점</b>이 그런 날이다.
 *
 * <p>비용은 쟀다 — 시장 한정 {@code max(trade_date)}가 <b>65~86ms</b>다
 * ({@code price_daily} 2,224,704행, {@code idx_price_date}가 있다). 격자 하나당 한 번이라
 * 무시할 수준이고, {@code stock} 조인을 피하려고 시장별 사본 테이블을 두지 않는다.
 */
@Component
public class AsOfCalendar {

    private final JdbcTemplate jdbcTemplate;

    public AsOfCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 그 시장들의 저장된 시세 마지막 거래일. 라이브 화면이 보는 날짜와 같다. */
    public LocalDate latestTradingDay(Collection<String> marketCodes) {
        return jdbcTemplate.queryForObject(
                "SELECT max(p.trade_date) FROM price_daily p JOIN stock s ON s.id = p.stock_id"
                        + " WHERE s.market_code IN (" + placeholders(marketCodes) + ")",
                LocalDate.class, marketCodes.toArray());
    }

    /**
     * {@code target} <b>이하</b>의 가장 가까운 거래일. 없으면 null이다.
     *
     * <p>이후가 아니라 이전으로 가는 이유는 미래를 보지 않기 위해서다 — 4월 1일이 휴장이라고
     * 4월 2일을 쓰면 그 하루만큼 미래의 시세로 판정하게 된다.
     */
    public LocalDate onOrBefore(LocalDate target, Collection<String> marketCodes) {
        Object[] args = new Object[marketCodes.size() + 1];
        args[0] = target;
        int i = 1;
        for (String code : marketCodes) {
            args[i++] = code;
        }
        List<LocalDate> found = jdbcTemplate.queryForList(
                "SELECT max(p.trade_date) FROM price_daily p JOIN stock s ON s.id = p.stock_id"
                        + " WHERE p.trade_date <= ? AND s.market_code IN ("
                        + placeholders(marketCodes) + ")",
                LocalDate.class, args);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * {@code from}부터 {@code monthStep}개월 간격으로 그 시장의 거래일을 고른다.
     *
     * <p>{@code lastAsOf}를 넘지 않는다 — 수익률 축은 as-of + 6개월의 시세가 있어야 하므로
     * 마지막 as-of가 저장된 마지막 거래일보다 6개월 앞이어야 한다. 그 계산은 호출부의 몫이다.
     */
    public List<LocalDate> every(LocalDate from, LocalDate lastAsOf, int monthStep,
                                 Collection<String> marketCodes) {
        List<LocalDate> grid = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(lastAsOf);
             cursor = cursor.plusMonths(monthStep)) {
            LocalDate tradingDay = onOrBefore(cursor, marketCodes);
            // 같은 거래일이 두 번 들어가면 회전율이 1.0으로 나와 "아무것도 안 바뀌었다"로 읽힌다.
            if (tradingDay != null && (grid.isEmpty() || !tradingDay.equals(grid.getLast()))) {
                grid.add(tradingDay);
            }
        }
        return grid;
    }

    /**
     * 그 시장 <b>재무의 최초 회계연도</b>. 격자의 하한을 대상별로 정하는 데 쓴다.
     *
     * <p><b>상수로 박지 않는 이유</b>: 국내는 FY2015부터, 미국은 FY2018부터다(실측).
     * 국내 바닥으로 정한 하한을 미국에 그대로 쓰면 {@code PROFIT_STREAK_Y >= 7} 같은
     * 다년 게이트가 <b>앞쪽 8시점에서 전 종목 판정불가</b>가 되는데, 그 0은 게이트가 아니라
     * <b>적재 범위 탓</b>이다 — 격자 하한이라는 장치가 애초에 막으려던 바로 그 실패다.
     */
    public Integer earliestFiscalYear(Collection<String> marketCodes) {
        return jdbcTemplate.queryForObject(
                "SELECT min(f.fiscal_year) FROM financial_statement f JOIN stock s ON s.id = f.stock_id"
                        + " WHERE f.is_latest AND s.market_code IN ("
                        + placeholders(marketCodes) + ")",
                Integer.class, marketCodes.toArray());
    }

    /**
     * {@code from}부터 {@code to}까지 그 시장의 <b>모든 거래일</b>.
     *
     * <p>{@link #every}의 분기 격자와 용도가 다르다. 그쪽은 조건축의 성질을 훑는 것이라
     * 성긴 격자로 충분하지만, <b>백테스트 진입일은 「그 전략을 처음 통과한 날」이라</b>
     * 하루라도 건너뛰면 진입가가 달라진다 — 커스텀 전략 백테스트 규약이 요구하는 해상도가
     * 이것이다.
     *
     * <p>as-of는 <b>이 시장의</b> 거래일이어야 한다. 시장을 안 나누고 합집합을 쓰면 국내
     * 휴장·미국 개장일이 국내 as-of로 들어가 {@code computeAt}이 빈 맵을 내고, 그 0이
     * 게이트 탓으로 읽힌다 — {@link #placeholders}가 막는 실패와 같은 것이다.
     */
    public List<LocalDate> everyTradingDay(LocalDate from, LocalDate to,
                                           Collection<String> marketCodes) {
        Object[] args = new Object[marketCodes.size() + 2];
        args[0] = from;
        args[1] = to;
        int i = 2;
        for (String code : marketCodes) {
            args[i++] = code;
        }
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT p.trade_date FROM price_daily p JOIN stock s ON s.id = p.stock_id"
                        + " WHERE p.trade_date BETWEEN ? AND ? AND s.market_code IN ("
                        + placeholders(marketCodes) + ") ORDER BY p.trade_date",
                LocalDate.class, args);
    }

    /** {@code IN (?, ?, …)}. 빈 컬렉션은 SQL이 성립하지 않으므로 호출부의 계약 위반이다. */
    private static String placeholders(Collection<String> marketCodes) {
        if (marketCodes == null || marketCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "시장을 고르지 않았다 — 기본값을 주면 국내 휴장·미국 개장일이 as-of로 잡혀"
                            + " 국내가 통째로 비는데 어떤 에러도 나지 않는다(ADR-0108)");
        }
        return String.join(", ", java.util.Collections.nCopies(marketCodes.size(), "?"));
    }
}
