package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 52주 고·저 대비 계산의 공통부.
 *
 * <p>두 계산기(최저가 대비·최고가 대비)가 창 길이와 축척 보정을 똑같이 해야 한다. 각자
 * 구현하면 한쪽만 {@link PriceScaling}을 빠뜨려도 값이 그럴듯해서 드러나지 않는다.
 *
 * <p>창 최소·최대를 <b>단조 덱</b>으로 미끄러뜨린다. 매 시점 252봉을 다시 훑으면 종목당
 * 봉 수 × 252번의 비교가 되고, 워밍업이 긴 지표가 늘어 러너가 읽는 봉 수가 커질수록 그 곱이
 * 그대로 커진다. 덱은 각 봉이 한 번 들어가고 한 번 나오므로 봉 수에 선형이다.
 */
final class FiftyTwoWeekRange {

    /** 약 1년. <b>거래일</b> 기준이다 — 달력 1년으로 세면 휴장일 수에 따라 표본이 달라진다. */
    static final int WINDOW = 252;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private FiftyTwoWeekRange() {
    }

    /**
     * 창의 극값 대비 현재 수정종가의 변화율(%).
     *
     * @param fromLow true면 최저가 대비(결과가 0 이상), false면 최고가 대비(결과가 0 이하)
     */
    static List<IndicatorPoint> percentFromExtreme(List<PriceBar> bars, boolean fromLow) {
        if (bars.size() < WINDOW) {
            return List.of();
        }

        // 값이 유리한 쪽으로 단조인 후보만 남긴다. 앞쪽이 언제나 창의 극값이다.
        Deque<Integer> candidates = new ArrayDeque<>();
        List<IndicatorPoint> points = new ArrayList<>(bars.size() - WINDOW + 1);

        for (int i = 0; i < bars.size(); i++) {
            BigDecimal value = extremeOf(bars.get(i), fromLow);

            // 새 값에 밀려나는 후보를 뒤에서 걷어낸다. 뒤에 있으면서 더 나쁜 값은
            // 앞으로도 극값이 될 수 없다.
            while (!candidates.isEmpty()) {
                BigDecimal tail = extremeOf(bars.get(candidates.peekLast()), fromLow);
                boolean dominated = fromLow ? tail.compareTo(value) >= 0 : tail.compareTo(value) <= 0;
                if (!dominated) {
                    break;
                }
                candidates.pollLast();
            }
            candidates.addLast(i);

            // 창을 벗어난 후보를 앞에서 버린다.
            if (candidates.peekFirst() <= i - WINDOW) {
                candidates.pollFirst();
            }

            if (i < WINDOW - 1) {
                continue;
            }

            BigDecimal extreme = extremeOf(bars.get(candidates.peekFirst()), fromLow);
            if (extreme.signum() <= 0) {
                continue;
            }
            BigDecimal ratio = bars.get(i).adjClose()
                    .divide(extreme, MovingAverages.MC)
                    .subtract(BigDecimal.ONE)
                    .multiply(HUNDRED)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(bars.get(i).tradeDate(), ratio));
        }
        return points;
    }

    private static BigDecimal extremeOf(PriceBar bar, boolean fromLow) {
        return fromLow ? PriceScaling.adjustedLow(bar) : PriceScaling.adjustedHigh(bar);
    }
}
