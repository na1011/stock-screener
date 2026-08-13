package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * ADX(14) — 추세의 <b>강도</b>. 방향은 말하지 않는다.
 *
 * <p>와일더의 원안이다. 고가·저가의 전일 대비 확장폭에서 +DM·−DM을 뽑아 실체범위로 나눠
 * +DI·−DI를 얻고, 그 격차 {@code DX = 100 × |+DI − −DI| / (+DI + −DI)}를 다시 평활한 것이
 * ADX다. <b>평활은 두 단계</b>라 첫 값이 28번째 봉에서야 선다.
 *
 * <p>MUST: 와일더 평활을 단순이동평균으로 바꾸지 않는다. {@code (이전 × 13 + 새 값) / 14}이고
 * SMA와는 다른 값을 낸다 — 그런데 <b>둘 다 0~100 안에 떨어져</b> 바꿔도 그럴듯해 보인다.
 * {@link Atr14Calculator}가 같은 평활을 쓴다.
 *
 * <p>MUST: 고가·저가를 {@link PriceScaling}으로 보정한다. 실체범위는 {@code Atr14Calculator}의
 * 것을 그대로 쓰지만 <b>±DM은 여기서 직접 계산</b>하므로 그쪽만 챙기고 이쪽을 원주가로 두면
 * 분할일에 방향성 지수만 튄다.
 *
 * <p>이 지표를 {@code OSCILLATOR}에 두는 것은 ADR-0057의 잣대("기준선을 두고 진동하는가")를
 * 넓힌 것이다 — 경위는 ADR-0058에 있다.
 */
@Component
@IndicatorMeta(
        name = "추세 강도(ADX)",
        description = "최근 흐름이 한 방향으로 얼마나 일관되게 움직였는지를 0~100으로 나타낸 값. "
                + "오르는지 내리는지는 말하지 않고 «추세가 있는가»만 잰다.",
        interpretation = "25를 넘으면 방향이 뚜렷한 추세장, 20 아래면 방향 없이 오가는 구간으로 본다. "
                + "값이 낮을 때는 다른 오실레이터의 과열·침체 신호가 잘 맞고, "
                + "높을 때는 과열이어도 추세가 이어지는 경우가 많다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.RATIO,
        decimalScale = 2,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 218,
        bands = {
                @IndicatorMeta.Band(label = "추세장", from = 25),
                @IndicatorMeta.Band(label = "전환 구간", from = 20, to = 25),
                @IndicatorMeta.Band(label = "비추세", to = 20)
        }
)
public class Adx14Calculator implements IndicatorCalculator {

    public static final String CODE = "ADX_14";
    private static final int PERIOD = 14;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // TR·±DM은 두 번째 봉부터 나오고(N−1개), 첫 평활이 그중 14개를 먹어 첫 DX가 14번째다.
        // ADX는 그 DX를 다시 14개 모아야 하므로 첫 값이 27번 인덱스 = 28봉째다.
        return DataRequirement.priceBars(2 * PERIOD);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() < 2 * PERIOD) {
            return List.of();
        }

        int moves = bars.size() - 1;
        BigDecimal[] trueRanges = new BigDecimal[moves];
        BigDecimal[] plusDm = new BigDecimal[moves];
        BigDecimal[] minusDm = new BigDecimal[moves];

        for (int i = 1; i < bars.size(); i++) {
            PriceBar current = bars.get(i);
            PriceBar previous = bars.get(i - 1);
            trueRanges[i - 1] = Atr14Calculator.trueRange(current, previous);

            // 위로 넓힌 폭과 아래로 넓힌 폭 중 **큰 쪽만** 방향성으로 센다. 둘 다이거나
            // 둘 다 아니면 그날은 방향이 없다.
            BigDecimal up = PriceScaling.adjustedHigh(current)
                    .subtract(PriceScaling.adjustedHigh(previous));
            BigDecimal down = PriceScaling.adjustedLow(previous)
                    .subtract(PriceScaling.adjustedLow(current));
            plusDm[i - 1] = up.signum() > 0 && up.compareTo(down) > 0 ? up : BigDecimal.ZERO;
            minusDm[i - 1] = down.signum() > 0 && down.compareTo(up) > 0 ? down : BigDecimal.ZERO;
        }

        BigDecimal smoothedTr = sum(trueRanges, 0, PERIOD);
        BigDecimal smoothedPlus = sum(plusDm, 0, PERIOD);
        BigDecimal smoothedMinus = sum(minusDm, 0, PERIOD);

        // DX를 모으는 동안은 ADX가 아직 없다. 첫 ADX는 DX 14개의 단순평균이고 그 뒤로
        // 와일더 평활이 이어진다 — 원안이 그렇다.
        List<BigDecimal> dxWindow = new ArrayList<>(PERIOD);
        BigDecimal adx = null;
        List<IndicatorPoint> points = new ArrayList<>();

        for (int i = PERIOD - 1; i < moves; i++) {
            if (i >= PERIOD) {
                smoothedTr = wilder(smoothedTr, trueRanges[i]);
                smoothedPlus = wilder(smoothedPlus, plusDm[i]);
                smoothedMinus = wilder(smoothedMinus, minusDm[i]);
            }
            if (smoothedTr.signum() == 0) {
                // 14일간 실체범위 합이 0이다(거래정지). 방향성을 정의할 수 없다.
                continue;
            }

            BigDecimal plusDi = smoothedPlus.multiply(HUNDRED)
                    .divide(smoothedTr, MovingAverages.MC);
            BigDecimal minusDi = smoothedMinus.multiply(HUNDRED)
                    .divide(smoothedTr, MovingAverages.MC);
            BigDecimal diSum = plusDi.add(minusDi);
            if (diSum.signum() == 0) {
                // 위로도 아래로도 넓힌 적이 없다. DX의 분모가 0이다.
                continue;
            }
            BigDecimal dx = plusDi.subtract(minusDi).abs()
                    .multiply(HUNDRED).divide(diSum, MovingAverages.MC);

            if (adx == null) {
                dxWindow.add(dx);
                if (dxWindow.size() < PERIOD) {
                    continue;
                }
                BigDecimal total = BigDecimal.ZERO;
                for (BigDecimal value : dxWindow) {
                    total = total.add(value);
                }
                adx = total.divide(BigDecimal.valueOf(PERIOD), MovingAverages.SCALE,
                        RoundingMode.HALF_UP);
            } else {
                adx = wilder(adx, dx);
            }
            // trueRanges[i]는 bars[i+1]의 것이다 — 첫 봉에는 실체범위가 없어 한 칸 밀려 있다.
            points.add(new IndicatorPoint(bars.get(i + 1).tradeDate(),
                    adx.setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }

    /**
     * 와일더 평활 {@code (이전 × 13 + 새 값) / 14}. 단순이동평균과 다른 값을 낸다.
     *
     * <p>package-private인 것은 <b>테스트가 이 식을 직접 못박기 위해서</b>다. ADX 출력만 보면
     * 평활을 SMA로 바꿔도 값이 0~100 안에 있어 그럴듯하고, 그런 테스트는 초록이라는 사실로
     * 거짓 안심만 준다.
     */
    static BigDecimal wilder(BigDecimal previous, BigDecimal added) {
        return previous.multiply(BigDecimal.valueOf(PERIOD - 1L)).add(added)
                .divide(BigDecimal.valueOf(PERIOD), MovingAverages.SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(BigDecimal[] values, int from, int count) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = from; i < from + count; i++) {
            total = total.add(values[i]);
        }
        return total;
    }
}
