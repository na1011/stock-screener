package com.stockscreener.pipeline.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * "오늘 데이터가 없는 것"이 휴장인지 수집 실패인지 판정한다.
 *
 * <p>IMPORTANT — 이 구분이 없으면 매 주말·공휴일마다 850건의 실패 알림이 오고, 그러면
 * 아무도 알림을 보지 않게 된다. 그 상태에서 진짜 장애가 나도 묻힌다.
 *
 * <p>공휴일 달력을 따로 두지 않는 이유는 두 시장의 휴장일을 정확히 유지하는 비용이 크고,
 * 그 자체가 또 하나의 갱신 대상이 되기 때문이다. 대신 <b>관측</b>으로 판정한다 —
 * 한 종목만 비면 그 종목의 실패이고, 대상 종목 대부분이 비면 휴장이다. 이 판정은
 * 달력 없이도 두 시장·모든 공휴일에 대해 자동으로 맞는다.
 */
@Component
public class MarketCalendar {

    /**
     * 휴장으로 판정하는 결측 비율. 이 비율 이상이 비면 휴장으로 본다.
     *
     * <p>1.0이 아닌 이유는 거래정지 종목이 섞여 있어도 판정이 성립해야 하기 때문이다.
     * 반대로 너무 낮으면 소스 장애를 휴장으로 오인해 진짜 실패를 놓친다.
     */
    private static final double HOLIDAY_THRESHOLD = 0.9;

    private final JdbcTemplate jdbcTemplate;

    public MarketCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 주말인가. 두 시장 모두 토·일에 열지 않으므로 소스를 호출하기 전에 거를 수 있다.
     *
     * <p>시장의 시간대로 판단해야 한다. 한국 시각 월요일 오전은 뉴욕에서 아직 일요일이다.
     */
    public boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    /**
     * 관측 결과로 휴장을 판정한다.
     *
     * @param targetCount  수집을 시도한 종목 수
     * @param emptyCount   데이터가 비어 돌아온 종목 수
     * @return 휴장으로 볼 수 있으면 true. 이 경우 실패 알림을 보내지 않는다.
     */
    public boolean looksLikeHoliday(int targetCount, int emptyCount) {
        if (targetCount == 0) {
            return false;
        }
        return (double) emptyCount / targetCount >= HOLIDAY_THRESHOLD;
    }

    /**
     * 해당 시장에서 실제로 거래가 관측된 가장 최근 일자.
     *
     * <p>일일 갱신의 대상 일자를 정할 때 쓴다. 달력이 아니라 저장된 시세를 근거로 삼는 이유는,
     * 그것이 "우리가 아는 마지막 거래일"이라는 질문에 대한 유일하게 정확한 답이기 때문이다.
     *
     * @return 시세가 하나도 없으면 null
     */
    public LocalDate lastObservedTradingDate(String marketCode) {
        return jdbcTemplate.queryForObject("""
                SELECT max(p.trade_date) FROM price_daily p
                JOIN stock s ON s.id = p.stock_id
                WHERE s.market_code = ?
                """, LocalDate.class, marketCode);
    }
}
