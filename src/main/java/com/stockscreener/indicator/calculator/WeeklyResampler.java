package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.PriceBar;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/**
 * 일봉을 ISO 주봉으로 접는다. <b>수정 축척으로</b> 접는다.
 *
 * <p>일목 패키지 밖에 있는 이유는 <b>소비자가 둘이기 때문</b>이다 — 주봉 일목 분석과
 * {@code BETA_104W}(2년 주간 베타)가 같은 주 경계를 써야 한다. 각자 접으면 「같은 주」의
 * 뜻이 갈리는데 <b>두 값 다 그럴듯해서 드러나지 않는다.</b>
 *
 * <p>IMPORTANT — 주 경계는 <b>"진행 중인 주 포함"</b>으로 모든 일자에 일관 적용한다
 * (ADR-0046 §승인된 이탈 3). Pine의 {@code request.security(…, "W", …)}는 비대칭이라
 * <b>실시간 봉에서는 진행 중인 주, 과거 봉에서는 마지막 확정 주</b>를 주는데, Pine Screener는
 * 마지막 봉만 평가하므로 사용자가 보는 것은 전자다. 그쪽에 맞춘다.
 * <b>결과로 과거 일자는 TradingView 차트와 값이 다르며 그것은 버그가 아니다.</b>
 *
 * <p>주 판정에 {@link WeekFields#ISO}를 쓴다 — 월요일 시작이고 KRX 주간과 맞는다.
 * 휴장이 섞여 4일짜리 주가 나오는 것은 정상이며 그것으로 완결 여부를 판정하지 않는다.
 */
public final class WeeklyResampler {

    private static final WeekFields ISO = WeekFields.ISO;

    private WeeklyResampler() {
    }

    /**
     * 오름차순 일봉을 오름차순 주봉으로 접는다. <b>마지막 원소가 진행 중인 주</b>일 수 있다.
     *
     * <p>MUST: 거래량에 {@code PriceScaling.adjustedVolume}을 쓴다. 가격과 <b>반대 방향</b>이며
     * (ADR-0051) 뒤집혀도 값은 그럴듯하다. 5:1 분할이면 분할 <b>전</b> 거래량이 5배가 되어야
     * 분할 후와 같은 주식 수를 뜻하고, 그것이 틀리면 주봉 OBV <b>누적</b>에 영구히 남아
     * {@code drv_26 → drv_41 → score_d2}(±3)가 틀린다.
     */
    public static List<WeeklyBar> resample(List<PriceBar> dailyBars) {
        List<WeeklyBar> weeks = new ArrayList<>(dailyBars.size() / 4 + 2);
        long currentKey = Long.MIN_VALUE;

        LocalDate endDate = null;
        double open = 0;
        double high = 0;
        double low = 0;
        double close = 0;
        double volume = 0;
        int days = 0;

        for (PriceBar bar : dailyBars) {
            long key = weekKey(bar.tradeDate());
            double barHigh = PriceScaling.adjustedHigh(bar).doubleValue();
            double barLow = PriceScaling.adjustedLow(bar).doubleValue();
            double barClose = bar.adjClose().doubleValue();
            double barOpen = PriceScaling.adjusted(bar, bar.open()).doubleValue();
            double barVolume = PriceScaling.adjustedVolume(bar).doubleValue();

            if (key != currentKey) {
                if (days > 0) {
                    weeks.add(new WeeklyBar(endDate, open, high, low, close, volume, days));
                }
                currentKey = key;
                open = barOpen;
                high = barHigh;
                low = barLow;
                volume = 0;
                days = 0;
            }
            high = Math.max(high, barHigh);
            low = Math.min(low, barLow);
            close = barClose;
            endDate = bar.tradeDate();
            volume += barVolume;
            days++;
        }
        if (days > 0) {
            weeks.add(new WeeklyBar(endDate, open, high, low, close, volume, days));
        }
        return weeks;
    }

    /**
     * 같은 주에 속하는지 가르는 키. <b>지수처럼 {@link PriceBar}가 아닌 시계열을 같은 주
     * 경계로 접을 때도 이것을 쓴다</b> — 종목과 지수의 「같은 주」가 갈리면 회귀가
     * 어긋난 짝을 맞추면서 값을 낸다.
     *
     * <p>연도를 {@code weekBasedYear}로 잡는 것이 중요하다. 달력 연도를 쓰면 <b>12월 마지막 주와
     * 1월 첫 주가 갈라져</b> 매년 한 주가 둘로 쪼개지는데, 그 주만 봉이 짧아질 뿐 에러는 없다.
     */
    public static long weekKey(LocalDate date) {
        return date.get(ISO.weekBasedYear()) * 100L + date.get(ISO.weekOfWeekBasedYear());
    }
}
