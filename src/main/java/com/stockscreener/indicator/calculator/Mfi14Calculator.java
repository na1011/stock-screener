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
 * 자금흐름지수(MFI, 14) — 거래량을 가중한 RSI.
 *
 * <p>전형가격이 오른 날의 {@code TP × 거래량}을 더하고 내린 날의 것을 따로 더해, 둘의 비로
 * 0~100을 낸다. RSI가 가격 변화만 보는 반면 이것은 <b>그 변화에 거래량이 실렸는가</b>를 함께
 * 본다 — 그래서 RSI와 갈리는 자리가 신호가 된다.
 *
 * <p>{@code OBV_T_13}과도 다르다. OBV는 <b>누적 방향</b>의 추세를 재고 이것은 <b>최근 14일의
 * 균형</b>을 0~100으로 잰다. 전자는 "쌓이고 있는가", 후자는 "지금 과열인가"다.
 *
 * <p>MUST: 전형가격과 거래량을 <b>둘 다</b> 보정한다. 한 봉만 놓고 보면 {@code adjusted()}가
 * {@code × adjClose/close}이고 {@code adjustedVolume()}이 그 <b>역수</b>라 {@code TP × 거래량}이
 * 보정에 불변이지만, 그 사실이 함정이다 — 이 지표가 실제로 쓰는 것은 <b>봉을 가로지르는</b>
 * {@code TP_t} 대 {@code TP_{t−1}} 비교이고 거기서 축척이 갈린다. 그리고 <b>한쪽만 보정하면</b>
 * 곱이 불변이던 성질까지 깨져 분할일의 자금흐름이 배수만큼 부풀거나 줄어든다.
 */
@Component
@IndicatorMeta(
        name = "자금흐름지수(14)",
        description = "최근 14일 동안 오른 날과 내린 날에 각각 얼마나 많은 돈이 오갔는지를 "
                + "견주어 0~100으로 나타낸 값. 거래량을 함께 보는 RSI라고 보면 된다.",
        interpretation = "80을 넘으면 사자가 몰린 과열, 20 아래면 팔자가 몰린 침체로 본다. "
                + "주가는 오르는데 이 값이 함께 오르지 않으면 상승에 돈이 따라붙지 않는다는 뜻이라 "
                + "되돌림을 의심한다.",
        category = IndicatorCategory.OSCILLATOR,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 212,
        bands = {
                @IndicatorMeta.Band(label = "과열", from = 80),
                @IndicatorMeta.Band(label = "중립", from = 20, to = 80),
                @IndicatorMeta.Band(label = "침체", to = 20)
        }
)
public class Mfi14Calculator implements IndicatorCalculator {

    public static final String CODE = "MFI_14";
    private static final int PERIOD = 14;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 첫 봉은 전일 전형가격이 없어 방향을 못 정한다. 14번 비교하려면 15봉이 든다.
        return DataRequirement.priceBars(PERIOD + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<PriceBar> bars = context.priceBars();
        if (bars.size() <= PERIOD) {
            return List.of();
        }

        BigDecimal[] typical = new BigDecimal[bars.size()];
        BigDecimal[] rawFlow = new BigDecimal[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            typical[i] = Cci20Calculator.typicalPrice(bars.get(i));
            rawFlow[i] = typical[i].multiply(PriceScaling.adjustedVolume(bars.get(i)));
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - PERIOD);
        for (int end = PERIOD; end < bars.size(); end++) {
            BigDecimal positive = BigDecimal.ZERO;
            BigDecimal negative = BigDecimal.ZERO;
            for (int i = end - PERIOD + 1; i <= end; i++) {
                int direction = typical[i].compareTo(typical[i - 1]);
                if (direction > 0) {
                    positive = positive.add(rawFlow[i]);
                } else if (direction < 0) {
                    negative = negative.add(rawFlow[i]);
                }
                // 전형가격이 같은 날은 어느 쪽에도 넣지 않는다. 원안이 그렇다.
            }

            if (positive.signum() == 0 && negative.signum() == 0) {
                // 14일간 전형가격이 한 번도 안 움직였다(대개 거래정지다). 균형을 정의할 수 없다.
                continue;
            }
            BigDecimal mfi;
            if (negative.signum() == 0) {
                // 내린 날이 하나도 없다. 정의되지 않은 것이 아니라 "완전한 매수 우위"라는
                // 실제 상태이므로 100을 낸다 — 행을 빼면 가장 강한 종목이 조건에서 사라진다.
                mfi = HUNDRED;
            } else {
                BigDecimal ratio = positive.divide(negative, MovingAverages.MC);
                mfi = HUNDRED.subtract(
                        HUNDRED.divide(BigDecimal.ONE.add(ratio), MovingAverages.MC));
            }
            points.add(new IndicatorPoint(bars.get(end).tradeDate(),
                    mfi.setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
