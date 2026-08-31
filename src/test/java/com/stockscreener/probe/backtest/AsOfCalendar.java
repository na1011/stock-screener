package com.stockscreener.probe.backtest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * as-of를 <b>실재하는 거래일로만</b> 고른다(P17, ADR-0071).
 *
 * <p>휴장일을 as-of로 주면 {@code IndicatorRunner.computeAt}이 전 종목 빈 맵을 낸다 —
 * 마지막 봉의 일자가 {@code asOf}와 정확히 같을 때만 값을 내기 때문이다. 그러면 필수 조건이
 * 전부 판정불가가 되어 <b>통과가 0</b>이 되는데, 어떤 에러도 나지 않아 <b>게이트 탓으로
 * 읽기 쉬운 0</b>이 된다. 달력으로 날짜를 만들지 않는 이유가 그것이다.
 *
 * <p>거래일의 정본은 {@code price_daily}다. 국내 휴장일 목록을 코드로 들고 있지 않고, 들면
 * 소스와 갈릴 때 <b>갈렸다는 사실이 통과 0으로만 드러난다.</b>
 *
 * <p><b>세 메서드가 전부 시장을 안 가른다</b>({@link #latestTradingDay}·{@link #onOrBefore}·
 * {@link #every}). {@code price_daily}에 미국 봉이 들어와 <b>미국 전용 거래일이 176일</b>
 * 있는데(실측 2026-08-31) 그 날짜를 as-of로 집으면 국내 종목이 전부 빈 맵을 받고
 * {@code computeAt}이 아무 값도 안 낸다 — 그 0은 <b>게이트 탓으로 읽히고 어떤 에러도 나지
 * 않는다.</b> 이 클래스가 애초에 피하려던 바로 그 실패다.
 *
 * <p><b>지금은 안 걸린다</b> — 두 시장의 마지막 거래일이 우연히 같고(둘 다 2026-08-28),
 * 하네스를 국내 프리셋으로만 돌리기 때문이다. <b>결함이 되는 조건은 둘</b>:
 * 미국이 국내보다 늦은 거래일을 갖는 순간(휴장이 갈리면 바로 그렇다), 또는
 * <b>미국 유니버스를 대상으로 이 하네스를 돌리는 순간</b>. 그때는 {@code market_code}를
 * 인자로 받아 세 질의를 전부 좁혀야 한다.
 */
@Component
public class AsOfCalendar {

    private final JdbcTemplate jdbcTemplate;

    public AsOfCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 저장된 시세의 마지막 거래일. 라이브 화면이 보는 날짜와 같다. */
    public LocalDate latestTradingDay() {
        return jdbcTemplate.queryForObject(
                "SELECT max(trade_date) FROM price_daily", LocalDate.class);
    }

    /**
     * {@code target} <b>이하</b>의 가장 가까운 거래일. 없으면 null이다.
     *
     * <p>이후가 아니라 이전으로 가는 이유는 미래를 보지 않기 위해서다 — 4월 1일이 휴장이라고
     * 4월 2일을 쓰면 그 하루만큼 미래의 시세로 판정하게 된다.
     */
    public LocalDate onOrBefore(LocalDate target) {
        List<LocalDate> found = jdbcTemplate.queryForList(
                "SELECT max(trade_date) FROM price_daily WHERE trade_date <= ?",
                LocalDate.class, target);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * {@code from}부터 {@code monthStep}개월 간격으로 거래일을 고른다.
     *
     * <p>{@code lastAsOf}를 넘지 않는다 — 수익률 축은 as-of + 6개월의 시세가 있어야 하므로
     * 마지막 as-of가 저장된 마지막 거래일보다 6개월 앞이어야 한다. 그 계산은 호출부의 몫이다.
     */
    public List<LocalDate> every(LocalDate from, LocalDate lastAsOf, int monthStep) {
        List<LocalDate> grid = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(lastAsOf);
             cursor = cursor.plusMonths(monthStep)) {
            LocalDate tradingDay = onOrBefore(cursor);
            // 같은 거래일이 두 번 들어가면 회전율이 1.0으로 나와 "아무것도 안 바뀌었다"로 읽힌다.
            if (tradingDay != null && (grid.isEmpty() || !tradingDay.equals(grid.getLast()))) {
                grid.add(tradingDay);
            }
        }
        return grid;
    }
}
