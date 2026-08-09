package com.stockscreener.pipeline.collector;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 소스를 부르기 전에 거를 수 있는 날인가.
 *
 * <p>공휴일 달력을 두지 않는다. 두 시장의 휴장일을 정확히 유지하는 비용이 크고, 그 자체가
 * 또 하나의 갱신 대상이 되기 때문이다. 대신 <b>휴장은 관측으로 드러난다</b> — 대상일에
 * 시세가 한 건도 들어오지 않으면 {@code DailyUpdateJob}이 그것을 알린다. 그 알림은 휴장과
 * "소스가 아직 그날치를 안 냄"을 구분하지 않는데, 구분할 근거가 응답에 없기 때문이다.
 *
 * <p>주말만 여기서 거르는 이유는 그것이 <b>달력만으로 확실한</b> 유일한 경우이기 때문이다.
 * 나머지는 불러 봐야 안다.
 */
@Component
public class MarketCalendar {

    /**
     * 주말인가. 두 시장 모두 토·일에 열지 않으므로 소스를 호출하기 전에 거를 수 있다.
     *
     * <p>시장의 시간대로 판단해야 한다. 한국 시각 월요일 오전은 뉴욕에서 아직 일요일이다.
     */
    public boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
