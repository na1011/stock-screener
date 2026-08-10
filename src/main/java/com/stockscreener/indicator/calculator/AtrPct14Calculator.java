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
 * 변동성 비율 — {@code ATR(14) ÷ 수정종가 × 100}. 하루 평균 등락폭을 주가 대비 퍼센트로.
 *
 * <p>{@code ATR_14}가 이미 있는데 이것을 두는 이유는 <b>종목 간 비교</b>다. ATR은 주가 수준에
 * 비례해 5만원짜리와 5천원짜리를 나란히 놓을 수 없고, 그래서 ATR 본체에는 구간도 붙일 수
 * 없다(ADR-0023). 종가로 나누면 축척이 사라져 그제서야 "이 종목이 얼마나 흔들리는가"가 된다.
 * MACD를 ATR로 나눠 MACD-V를 만든 것과 같은 조작이다.
 *
 * <p>이 지표의 쓸모는 수익률이 아니라 <b>버틸 수 있는가</b>다. 하루 4%씩 움직이는 종목은 한 달이면
 * 일상적으로 ±15~20%를 오가며, 그 진폭에서 "단기 하락은 노이즈"라는 원칙은 지켜지지 않는다.
 *
 * <p>IMPORTANT — 부모인 {@code ATR_14}가 백필 횟수에 따라 값이 흔들리는 것이 관측돼 있다
 * ({@code docs/progress.md}). Wilder 평활의 시드가 읽어 온 봉의 앞쪽에서 잘리기 때문으로 보이며,
 * 이 지표는 그 성질을 그대로 물려받는다. 게이트로 쓸 때 그 사실을 함께 본다.
 */
@Component
@IndicatorMeta(
        name = "변동성 비율(ATR/주가)",
        description = "14일 평균 변동폭을 종가로 나눈 값. 하루에 평균 몇 퍼센트씩 움직이는지를 나타낸다.",
        interpretation = "값이 클수록 하루 등락이 크다. "
                + "4%면 한 달이면 일상적으로 ±15~20% 구간을 오간다는 뜻이라, "
                + "장기 보유를 전제한다면 그 진폭을 견딜 수 있는지 먼저 본다. "
                + "ATR과 달리 종목 간 비교가 가능하다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 121,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 5),
                @IndicatorMeta.Band(label = "높음", from = 3.5, to = 5),
                @IndicatorMeta.Band(label = "보통", from = 2, to = 3.5),
                @IndicatorMeta.Band(label = "낮음", to = 2)
        }
)
public class AtrPct14Calculator implements IndicatorCalculator {

    public static final String CODE = "ATR_PCT_14";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.derivedFrom(0, Atr14Calculator.CODE);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        NavigableMap<LocalDate, BigDecimal> atr = context.dependency(Atr14Calculator.CODE);
        if (atr.isEmpty()) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>();
        for (PriceBar bar : context.priceBars()) {
            BigDecimal range = atr.get(bar.tradeDate());
            if (range == null || bar.adjClose().signum() <= 0) {
                continue;
            }
            points.add(new IndicatorPoint(bar.tradeDate(),
                    range.divide(bar.adjClose(), MovingAverages.MC)
                            .multiply(HUNDRED)
                            .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
