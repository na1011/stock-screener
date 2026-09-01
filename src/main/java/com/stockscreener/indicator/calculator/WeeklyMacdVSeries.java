package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 주봉 MACD-V 계열을 <b>한 번만</b> 계산해 계산기 셋이 나눠 쓰게 하는 메모이저(ADR-0054의 형태).
 *
 * <p>{@code dependsOn} 채널로는 안 된다 — 그것이 나르는 것은 코드 하나의 스칼라 시계열이라
 * 본선·히스토그램·변화량 셋을 한 번의 계산에서 갈라 줄 수 없다. 셋이 각자 접으면 리샘플과
 * 평활이 세 벌이 되고, <b>한 벌만 고쳤을 때 값이 정상 범위 안이라 어긋난 것이 안 드러난다.</b>
 *
 * <p><b>진행 중인 주를 포함한다.</b> {@link WeeklyResampler}가 그렇게 접고(ADR-0046 §승인된 이탈 3)
 * 같은 화면의 {@code ICHI_OBV_T}도 그쪽이다 — 두 주봉 카드가 다른 주 경계를 쓰면 나란히 못 읽는다.
 * 대가는 <b>값이 주중에도 바뀐다</b>는 것이고, 그래서 주봉 골든크로스가 주중에 켜졌다 꺼질 수 있다.
 *
 * <p>MUST NOT: 마지막 완결 주와 진행 중인 주를 섞지 않는다. {@code resample}의 마지막 원소는
 * 진행 중인 주일 수 있고 {@code WeeklyBar.days}로는 그것을 판별할 수 없다(휴장이 섞이면 완결된
 * 주도 5일이 아니다). 여기서는 <b>그 거래일이 속한 주보다 앞선 주만</b> 완결로 보고, 진행 중인
 * 주는 거래일마다 평활을 <b>한 걸음</b>만 민다. 섞으면 최신 거래일에서 같은 주가 EMA 체인과
 * 스텝에 두 번 들어가는데 에러가 없다.
 *
 * <p>MUST NOT: 주봉 접기를 다시 짜지 않는다(ADR-0063). 부분 주도 그 주의 봉만 잘라
 * {@link WeeklyResampler#resample}에 넘긴다 — 첫 봉에서 {@code days == 0}이라 유령 주가 생기지
 * 않고 {@code open}이 그 봉에서 잡힌다.
 */
@Component
public class WeeklyMacdVSeries {

    /** 빠른 EMA 기간. {@link MacdCalculator}와 같은 값을 주봉에 건다. */
    static final int FAST = 12;
    /** 느린 EMA 기간. */
    static final int SLOW = 26;
    /** 시그널선 기간. */
    static final int SIGNAL = 9;
    /**
     * 변동성 기간. <b>원 논문은 26이지만 이 저장소의 일봉 MACD-V가 {@code ATR_14}를 쓴다.</b>
     * 여기서 갈리면 이름이 같은 두 카드가 다른 공식을 말하는데 값이 그럴듯해 안 드러난다.
     */
    static final int ATR_PERIOD = 14;

    /**
     * 선언하는 시세 봉 수.
     *
     * <p><b>표시용 숫자가 아니라 계약이 둘 걸린 값이다.</b> 하나는 {@code UnadjustedActionGuard}의
     * <b>억제 창</b>이고(ADR-0055 — 창이 미보정 기업행위를 삼키면 값을 내지 않는다), 다른 하나는
     * 평활 시드가 얼마나 남는가다. 「값을 내는 데 필요한 최소」(≈185봉)로 잡으면 주봉 EMA26의
     * 시드 잔여 가중치가 <b>수십 %</b>가 되고, 그 창 밖의 미보정 분할이 억제되지 않은 채
     * 주봉 종가에 절벽으로 들어오는데 <b>값은 나온다.</b>
     *
     * <p>{@code IchimokuIndicator.LOOKBACK}과 같은 값이라 <b>같은 화면의 두 주봉 카드가 같은
     * 날짜에 억제되고 같은 종목에서 사라진다.</b> 갈리면 사용자는 이유를 알 수 없다.
     *
     * <p>읽기 창은 안 움직인다 — {@code IndicatorRegistry.readWindowBars()}는
     * {@code maxEffectiveLookback() + 보존일수}이고 최댓값은 {@code EPS_CAGR_5Y}의 1,261이라
     * <b>그보다 작은 어떤 값도 창을 못 바꾼다</b>(ADR-0079).
     */
    static final int LOOKBACK = 1100;

    /**
     * 값을 내기 시작하는 완결 주 수.
     *
     * <p><b>「계산이 되는 최소」가 아니다.</b> 그것은 34주쯤이고, 거기서는 주봉 EMA26의 시드
     * 잔여 가중치가 {@code (1−2/27)^8 ≈ 0.54}라 값이 <b>종목의 성질이 아니라 읽기 창이 어디서
     * 시작했는가</b>를 반영한다. 200주면 {@code (1−2/27)^174 ≈ 1.5e−6}로 ADR-0055가 일봉에서
     * 근거로 든 {@code (13/14)^176 ≈ 2e−6}과 같은 자릿수다.
     */
    static final int MIN_COMPLETED_WEEKS = 200;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 그 거래일의 주봉 MACD-V 본선·히스토그램·1주 변화량. */
    public record Point(BigDecimal value, BigDecimal hist, BigDecimal histChange) {
    }

    /** 어떤 창이었는지. 같은 종목이라도 창이 다르면 평활 시드가 달라 결과가 다르다. */
    private record Key(long stockId, LocalDate first, LocalDate last, int bars) {
    }

    private Key cachedKey;
    private NavigableMap<LocalDate, Point> cachedValue;

    /**
     * 이 종목·이 창에서 산출 가능한 모든 거래일의 주봉 MACD-V 계열.
     *
     * <p>{@code synchronized}인 이유는 정확성이 아니라 캐시가 찢어지지 않게 하려는 것이다 —
     * 배치는 순차지만 관리자 화면이 잡을 띄우는 경로가 있어(ADR-0029) 같은 JVM에서 겹칠 수 있다.
     */
    public synchronized NavigableMap<LocalDate, Point> of(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return Collections.emptyNavigableMap();
        }
        Key key = new Key(context.stockId(), bars.getFirst().tradeDate(),
                bars.getLast().tradeDate(), bars.size());
        if (key.equals(cachedKey)) {
            return cachedValue;
        }
        NavigableMap<LocalDate, Point> value = compute(bars);
        cachedKey = key;
        cachedValue = value;
        return value;
    }

    private static NavigableMap<LocalDate, Point> compute(List<PriceBar> bars) {
        int[] weekStart = weekStarts(bars);
        int weeks = weekStart.length;
        if (weeks <= MIN_COMPLETED_WEEKS) {
            return Collections.emptyNavigableMap();
        }

        // 완결 주는 한 번만 접는다. 마지막 주는 그 자신이 진행 중일 수 있으므로 여기 담기더라도
        // 아래 루프가 자기 주를 완결로 쓰지 않는다(언제나 앞선 주까지만 본다).
        List<WeeklyBar> completed = new ArrayList<>(weeks);
        for (int w = 0; w < weeks; w++) {
            int end = (w + 1 < weeks) ? weekStart[w + 1] : bars.size();
            completed.add(WeeklyResampler.resample(bars.subList(weekStart[w], end)).getFirst());
        }

        BigDecimal[] fast = emaByWeek(completed, FAST);
        BigDecimal[] slow = emaByWeek(completed, SLOW);
        BigDecimal[] atr = atrByWeek(completed);
        BigDecimal[] macdV = macdVByWeek(fast, slow, atr);
        BigDecimal[] signal = signalByWeek(macdV);

        NavigableMap<LocalDate, Point> result = new TreeMap<>();
        for (int w = MIN_COMPLETED_WEEKS; w < weeks; w++) {
            int previous = w - 1;
            if (signal[previous] == null) {
                continue;
            }
            BigDecimal previousHist = macdV[previous].subtract(signal[previous]);
            int end = (w + 1 < weeks) ? weekStart[w + 1] : bars.size();
            for (int i = weekStart[w]; i < end; i++) {
                Point point = stepInto(bars.subList(weekStart[w], i + 1), completed.get(previous),
                        fast[previous], slow[previous], atr[previous], signal[previous],
                        previousHist);
                if (point != null) {
                    result.put(bars.get(i).tradeDate(), point);
                }
            }
        }
        return Collections.unmodifiableNavigableMap(result);
    }

    /**
     * 진행 중인 주를 한 걸음 민다. 직전 완결 주까지의 평활 상태에 <b>부분 주봉 하나</b>만 얹는다.
     *
     * <p>거래일마다 시계열 전체로 EMA를 다시 돌리면 종목당 주 수만큼 곱해진다.
     */
    private static Point stepInto(List<PriceBar> weekSoFar, WeeklyBar previousWeek,
            BigDecimal previousFast, BigDecimal previousSlow, BigDecimal previousAtr,
            BigDecimal previousSignal, BigDecimal previousHist) {
        WeeklyBar running = WeeklyResampler.resample(weekSoFar).getFirst();
        BigDecimal close = BigDecimal.valueOf(running.close());

        BigDecimal fast = MovingAverages.emaStep(previousFast, close, FAST);
        BigDecimal slow = MovingAverages.emaStep(previousSlow, close, SLOW);
        BigDecimal trueRange = Atr14Calculator.trueRange(BigDecimal.valueOf(running.high()),
                BigDecimal.valueOf(running.low()), BigDecimal.valueOf(previousWeek.close()));
        BigDecimal atr = Atr14Calculator.rmaStep(previousAtr, trueRange, ATR_PERIOD);
        if (atr.signum() == 0) {
            return null;
        }
        BigDecimal value = fast.subtract(slow)
                .divide(atr, MovingAverages.MC)
                .multiply(HUNDRED)
                .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
        BigDecimal signal = MovingAverages.emaStep(previousSignal, value, SIGNAL);
        BigDecimal hist = value.subtract(signal);
        return new Point(value, hist, hist.subtract(previousHist));
    }

    /** 각 주가 시작하는 봉의 인덱스. 한 번 훑는다 — 일자마다 다시 찾으면 봉 수의 제곱이 된다. */
    private static int[] weekStarts(List<PriceBar> bars) {
        List<Integer> starts = new ArrayList<>(bars.size() / 4 + 2);
        long current = Long.MIN_VALUE;
        for (int i = 0; i < bars.size(); i++) {
            long key = WeeklyResampler.weekKey(bars.get(i).tradeDate());
            if (key != current) {
                starts.add(i);
                current = key;
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    /**
     * 완결 주 종가의 EMA를 주 인덱스에 맞춰 편다.
     *
     * <p>{@link MovingAverages#ema}는 {@code values[period−1]}부터의 <b>평평한 목록</b>을 돌려준다.
     * 그 오프셋을 여기서 한 번 풀어 두지 않으면 기간이 다른 셋(12·26·9)의 정렬이 각각
     * 11·25·8만큼 어긋나는데, <b>하나만 틀려도 값이 그럴듯하다.</b>
     */
    private static BigDecimal[] emaByWeek(List<WeeklyBar> weeks, int period) {
        List<BigDecimal> closes = new ArrayList<>(weeks.size());
        for (WeeklyBar week : weeks) {
            closes.add(BigDecimal.valueOf(week.close()));
        }
        BigDecimal[] byWeek = new BigDecimal[weeks.size()];
        List<BigDecimal> ema = MovingAverages.ema(closes, period);
        for (int i = 0; i < ema.size(); i++) {
            byWeek[period - 1 + i] = ema.get(i);
        }
        return byWeek;
    }

    /**
     * 완결 주의 Wilder ATR. <b>첫 주는 직전 종가가 없어 실체범위가 {@code 고가 − 저가}다</b> —
     * 0을 직전 종가로 넣으면 갭이 주가만큼 벌어진 것으로 계산되어 시드가 통째로 부푼다.
     */
    private static BigDecimal[] atrByWeek(List<WeeklyBar> weeks) {
        BigDecimal[] trueRanges = new BigDecimal[weeks.size()];
        for (int w = 0; w < weeks.size(); w++) {
            WeeklyBar week = weeks.get(w);
            BigDecimal previousClose = (w == 0) ? null : BigDecimal.valueOf(weeks.get(w - 1).close());
            trueRanges[w] = Atr14Calculator.trueRange(BigDecimal.valueOf(week.high()),
                    BigDecimal.valueOf(week.low()), previousClose);
        }
        BigDecimal[] byWeek = new BigDecimal[weeks.size()];
        if (weeks.size() < ATR_PERIOD) {
            return byWeek;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int w = 0; w < ATR_PERIOD; w++) {
            sum = sum.add(trueRanges[w]);
        }
        byWeek[ATR_PERIOD - 1] = sum.divide(BigDecimal.valueOf(ATR_PERIOD),
                MovingAverages.SCALE, RoundingMode.HALF_UP);
        for (int w = ATR_PERIOD; w < weeks.size(); w++) {
            byWeek[w] = Atr14Calculator.rmaStep(byWeek[w - 1], trueRanges[w], ATR_PERIOD);
        }
        return byWeek;
    }

    private static BigDecimal[] macdVByWeek(BigDecimal[] fast, BigDecimal[] slow, BigDecimal[] atr) {
        BigDecimal[] byWeek = new BigDecimal[fast.length];
        for (int w = 0; w < byWeek.length; w++) {
            if (fast[w] == null || slow[w] == null || atr[w] == null || atr[w].signum() == 0) {
                continue;
            }
            byWeek[w] = fast[w].subtract(slow[w])
                    .divide(atr[w], MovingAverages.MC)
                    .multiply(HUNDRED)
                    .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP);
        }
        return byWeek;
    }

    /**
     * 본선의 9주 EMA. <b>본선이 끊기면 그 자리에서 다시 시드한다</b> — 재귀라 구멍 하나가
     * 그 뒤 전 구간에 남고, 이어 붙이면 값이 정상 범위 안이라 드러나지 않는다.
     */
    private static BigDecimal[] signalByWeek(BigDecimal[] macdV) {
        BigDecimal[] byWeek = new BigDecimal[macdV.length];
        int consumed = 0;
        BigDecimal seedSum = BigDecimal.ZERO;
        BigDecimal signal = null;
        for (int w = 0; w < macdV.length; w++) {
            if (macdV[w] == null) {
                consumed = 0;
                seedSum = BigDecimal.ZERO;
                signal = null;
                continue;
            }
            if (signal == null) {
                seedSum = seedSum.add(macdV[w]);
                consumed++;
                if (consumed < SIGNAL) {
                    continue;
                }
                signal = seedSum.divide(BigDecimal.valueOf(SIGNAL),
                        MovingAverages.SCALE, RoundingMode.HALF_UP);
            } else {
                signal = MovingAverages.emaStep(signal, macdV[w], SIGNAL);
            }
            byWeek[w] = signal;
        }
        return byWeek;
    }
}
