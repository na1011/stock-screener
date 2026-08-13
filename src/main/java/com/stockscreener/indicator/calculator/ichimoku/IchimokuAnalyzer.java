package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.PriceBar;
import com.stockscreener.indicator.calculator.ExcessReturn;
import com.stockscreener.indicator.calculator.PriceScaling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * 창 하나를 받아 <b>산출 가능한 모든 거래일</b>의 일목 분석을 낸다.
 *
 * <p>실행당 몇 일자가 나오는지는 창과 워밍업이 정한다 — 창 1,100봉에 워밍업 207주(≈971봉)라
 * <b>종목당 평균 136.8일자</b>다(2026-08-13 실측, 활성 315종목·합계 43,093 종목·일자).
 *
 * <p>비용의 요점은 <b>완결된 주의 파생값을 종목당 한 번만 만드는 것</b>이다({@link WeeklySeries}).
 * 일자마다 236주 전체를 다시 접으면 그 비용이 137배가 되고, 값이 맞아도 백필이 실용 시간 안에
 * 끝나지 않는다.
 */
public final class IchimokuAnalyzer {

    private static final WeekFields ISO = WeekFields.ISO;

    private IchimokuAnalyzer() {
    }

    /**
     * @param marketIndex <b>이 종목이 속한 시장</b>의 지수 종가(ADR-0053 §이탈 4). 비었거나 그
     *                    일자에 값이 없으면 <b>그 일자는 통째로 건너뛴다</b> — 0으로 접으면
     *                    D5가 상대 수익률에서 절대 수익률로 뜻이 바뀌는데 에러도 화면 흔적도 없다
     *                    (ADR-0046 §승인된 이탈 2). {@code ICHI_TIER}만 빼면 나머지 코드가
     *                    "절대 수익률" 뜻으로 화면에 나가므로 <b>30개를 함께 건너뛴다.</b>
     * @return 거래일 → 분석. 207 주봉을 못 채우는 구간과 지수 결손일은 담기지 않는다
     */
    public static NavigableMap<LocalDate, IchimokuAnalysis> analyze(
            List<PriceBar> bars, NavigableMap<LocalDate, BigDecimal> marketIndex) {

        NavigableMap<LocalDate, IchimokuAnalysis> result = new TreeMap<>();
        if (bars.isEmpty() || marketIndex == null || marketIndex.isEmpty()) {
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
            // MUST: 초과 수익률을 낼 수 없으면 그 일자를 통째로 건너뛴다(ADR-0046 §승인된 이탈 2).
            // ICHI_TIER만 빼면 나머지 코드가 "절대 수익률" 뜻으로 화면에 나가는데, D5는
            // bull/bear → conclusion·confidence → ext_signal까지 번진다.
            OptionalDouble excess = ExcessReturn.at(bars, marketIndex, i);
            if (excess.isEmpty()) {
                continue;
            }

            LocalDate date = bars.get(i).tradeDate();
            WeeklyWindow window = new WeeklyWindow(series, current, partials.get(i));
            WeeklyAnalysis weekly = WeeklyAnalyzer.analyze(window);
            result.put(date, IchimokuScoring.score(weekly, daily.kaizen(i), excess.getAsDouble()));
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
            long key = weekKey(bar.tradeDate());
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
            long key = weekKey(bars.get(i).tradeDate());
            if (key != currentKey) {
                currentKey = key;
                week++;
            }
            indexes[i] = week;
        }
        return indexes;
    }

    private static long weekKey(LocalDate date) {
        return date.get(ISO.weekBasedYear()) * 100L + date.get(ISO.weekOfWeekBasedYear());
    }
}
