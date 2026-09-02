package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.calculator.WeeklyBar;
import com.stockscreener.indicator.calculator.WeeklyResampler;
import com.stockscreener.indicator.PriceBar;
import com.stockscreener.indicator.calculator.ExcessReturn;
import com.stockscreener.indicator.calculator.PriceScaling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 창 하나를 받아 <b>산출 가능한 모든 거래일</b>의 일목 분석을 낸다.
 *
 * <p>실행당 몇 일자가 나오는지는 {@code min(창 − 워밍업 + 1, 보존 180)}이 정한다. 워밍업은
 * 207 주봉(≈971봉)이고, 창이 971 + 180 이상이면 <b>보존 경계가 먼저 자른다</b> —
 * 실측 종목당 평균 <b>177.4일자</b>(311종목, 서로 다른 일자 180, 2026-08-20).
 *
 * <p>비용의 요점은 <b>완결된 주의 파생값을 종목당 한 번만 만드는 것</b>이다({@link WeeklySeries}).
 * 일자마다 창 전체의 주봉을 다시 접으면 그 비용이 <b>산출 일자 수만큼</b> 곱해지고, 값이
 * 맞아도 백필이 실용 시간 안에 끝나지 않는다.
 */
public final class IchimokuAnalyzer {

    private IchimokuAnalyzer() {
    }

    /**
     * <p>시장 지수를 받는 것은 {@code drv_55}(26주 초과 수익률)가 D5 점수로 번지기 때문이다.
     * <b>지수가 없는 일자를 통째로 버리던 게이트는 철회됐다</b>(ADR-0046 §승인된 이탈 2) —
     * 관측 지표 열여덟은 지수를 쓰지 않으므로 함께 막으면 회귀다. 그 일자에는 D5에 기대는
     * 일곱만 {@code null}이 된다.
     *
     * @return 거래일 → 분석. 207 주봉을 못 채우는 구간은 담기지 않는다
     */
    public static NavigableMap<LocalDate, IchimokuAnalysis> analyze(
            List<PriceBar> bars, NavigableMap<LocalDate, BigDecimal> marketIndex) {
        NavigableMap<LocalDate, IchimokuAnalysis> result = new TreeMap<>();
        if (bars.isEmpty()) {
            return result;
        }

        List<WeeklyBar> weeks = WeeklyResampler.resample(bars);
        if (weeks.size() < WeeklySeries.MIN_WEEKS) {
            return result;
        }
        WeeklySeries series = WeeklySeries.of(weeks);
        DailySeries daily = DailySeries.of(bars);

        // 일자마다 "그 주의 시작부터 그날까지"의 집계가 필요하다 — 진행 중인 주를 포함하는
        // 의미론(ADR-0046 §승인된 이탈 3)이 그것을 요구한다.
        List<WeeklyBar> partials = runningWeeks(bars);
        int[] weekIndex = weekIndexPerDay(bars);

        for (int i = 0; i < bars.size(); i++) {
            int current = weekIndex[i];
            // 인덱스 0이 진행 중인 주이므로 그 주를 포함해 207개가 있어야 한다.
            if (current + 1 < WeeklySeries.MIN_WEEKS) {
                continue;
            }
            LocalDate date = bars.get(i).tradeDate();
            WeeklyWindow window = new WeeklyWindow(series, current, partials.get(i));
            WeeklyAnalysis weekly = WeeklyAnalyzer.analyze(window);
            // MUST: 지수가 없는 일자를 건너뛰지 않는다 — 그 게이트는 철회됐다(ADR-0046
            // §승인된 이탈 2). 초과 수익률에 기대는 일곱만 값을 못 내고, 그것은
            // IchimokuScoring이 null로 표현한다. 계산기 일곱은 priceAndMarketIndex를
            // 선언해 프레임워크가 그 일자를 애초에 거른다.
            result.put(date, IchimokuScoring.score(weekly, daily.kaizen(i),
                    ExcessReturn.at(bars, marketIndex, i)));
        }
        return result;
    }

    /** 일자마다 "그 주의 시작부터 그날까지"의 집계. */
    private static List<WeeklyBar> runningWeeks(List<PriceBar> bars) {
        List<WeeklyBar> partials = new ArrayList<>(bars.size());
        long currentKey = Long.MIN_VALUE;
        double open = 0;
        double high = 0;
        double low = 0;
        double volume = 0;
        int days = 0;

        for (PriceBar bar : bars) {
            long key = WeeklyResampler.weekKey(bar.tradeDate());
            double barHigh = PriceScaling.adjustedHigh(bar).doubleValue();
            double barLow = PriceScaling.adjustedLow(bar).doubleValue();
            if (key != currentKey) {
                currentKey = key;
                open = PriceScaling.adjusted(bar, bar.open()).doubleValue();
                high = barHigh;
                low = barLow;
                volume = 0;
                days = 0;
            }
            high = Math.max(high, barHigh);
            low = Math.min(low, barLow);
            volume += PriceScaling.adjustedVolume(bar).doubleValue();
            days++;
            partials.add(new WeeklyBar(bar.tradeDate(), open, high, low,
                    bar.adjClose().doubleValue(), volume, days));
        }
        return partials;
    }

    /** 일자마다 그 주가 주봉 목록에서 갖는 인덱스. */
    private static int[] weekIndexPerDay(List<PriceBar> bars) {
        int[] indexes = new int[bars.size()];
        long currentKey = Long.MIN_VALUE;
        int week = -1;
        for (int i = 0; i < bars.size(); i++) {
            long key = WeeklyResampler.weekKey(bars.get(i).tradeDate());
            if (key != currentKey) {
                currentKey = key;
                week++;
            }
            indexes[i] = week;
        }
        return indexes;
    }
}
