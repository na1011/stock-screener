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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/**
 * 200일선 이격도 — {@code 수정종가 ÷ SMA200}.
 *
 * <p>{@code ADJ_CLOSE > SMA_200 × 1.25} 같은 조건으로도 같은 것을 물을 수 있는데 지표로 두는
 * 이유는 <b>값을 보여야 하기 때문</b>이다. 조건은 참·거짓만 남기지만, 결과를 "덜 오른 순"으로
 * 줄 세우거나 "얼마나 떨어져 있는가"를 컬럼으로 읽으려면 값 자체가 있어야 한다.
 *
 * <p>무차원이라 종목 간 비교가 되고 구간을 붙일 수 있다 — SMA200 본체에 구간을 붙일 수 없는
 * 것과 대비된다(ADR-0023). 경계 1.25는 분할매수의 여지와 하방 완충을 함께 본 값이다:
 * 이격도가 크면 2·3차 진입 가격을 잡을 자리가 없고, 200일선까지의 낙폭 자체도 그만큼 커진다.
 *
 * <p>워밍업은 선언하지 않는다. 자기 몫이 0이고 부모(SMA200)의 200봉은 레지스트리가 합산한다.
 */
@Component
@IndicatorMeta(
        name = "200일선 이격도",
        description = "수정종가를 200일 이동평균으로 나눈 값. 장기 추세선에서 얼마나 떨어져 있는지를 배수로 나타낸다.",
        interpretation = "1보다 크면 200일선 위, 작으면 아래다. "
                + "1.25를 넘으면 단기 급등으로 추세선에서 크게 벌어진 상태이며, "
                + "조정 시 추세선까지의 낙폭도 그만큼 커진다. "
                + "0.9 아래는 장기 추세가 무너지고 있을 수 있다.",
        category = IndicatorCategory.TECHNICAL,
        valueType = IndicatorValueType.RATIO,
        decimalScale = 3,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 110,
        bands = {
                @IndicatorMeta.Band(label = "과열", from = 1.30),
                @IndicatorMeta.Band(label = "고평가 구간", from = 1.15, to = 1.30),
                @IndicatorMeta.Band(label = "추세선 부근", from = 0.95, to = 1.15),
                @IndicatorMeta.Band(label = "추세선 아래", from = 0.85, to = 0.95),
                @IndicatorMeta.Band(label = "추세 이탈", to = 0.85)
        }
)
public class Disparity200Calculator implements IndicatorCalculator {

    public static final String CODE = "DISPARITY_200";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(0, Sma200Calculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> sma = context.dependency(Sma200Calculator.CODE);
        if (sma.isEmpty()) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>();
        for (PriceBar bar : context.priceBars()) {
            BigDecimal reference = sma.get(bar.tradeDate());
            // 워밍업 구간에는 SMA200이 없다. 그 날은 이격도도 없다.
            if (reference == null || reference.signum() == 0) {
                continue;
            }
            points.add(new IndicatorPoint(bar.tradeDate(),
                    bar.adjClose().divide(reference, MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
