package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
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
 * 주가순자산비율(PBR) = 주가 ÷ 주당순자산.
 *
 * <p>PER과 같은 이유로 원주가({@link PriceBar#close()})를 쓴다.
 * 자본이 음수(자본잠식)면 PBR이 의미를 잃으므로 값을 내지 않는다.
 */
@Component
@IndicatorMeta(
        name = "PBR",
        description = "주가를 주당순자산으로 나눈 값. 회사의 장부상 순자산 대비 주가 수준을 나타낸다.",
        interpretation = "낮을수록 순자산 대비 주가가 싸다는 뜻이다. 1배 미만이면 장부가보다 싸게 거래되는 상태다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 2,
        defaultColumn = true,
        sortOrder = 20,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 3),
                @IndicatorMeta.Band(label = "높음", from = 1.5, to = 3),
                @IndicatorMeta.Band(label = "보통", from = 1, to = 1.5),
                // 1배가 장부가다. 이 경계는 PBR을 처음 보는 사람에게 가장 먼저 필요한 사실이라
                // 라벨에 넣는다 — interpretation의 같은 문장이 산문으로만 있었다.
                @IndicatorMeta.Band(label = "낮음 (장부가 미만)", from = 0.5, to = 1),
                @IndicatorMeta.Band(label = "매우 낮음", to = 0.5)
        }
)
public class PbrCalculator implements IndicatorCalculator {

    public static final String CODE = "PBR";
    private static final int SCALE = 8;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        if (quarters.isEmpty()) {
            return List.of();
        }

        BigDecimal equity = quarters.get(0).totalEquity();
        if (equity == null || equity.signum() <= 0) {
            // 자본잠식이면 음수 PBR이 나와 "저평가"로 보인다. 값을 내지 않는다.
            return List.of();
        }

        // 주식수를 봉마다 읽는다 — 유상증자·분할 구간에서 BPS가 달라져야 한다.
        List<IndicatorPoint> points = new ArrayList<>(context.priceBars().size());
        for (PriceBar bar : context.priceBars()) {
            BigDecimal shares = FinancialMath.sharesOf(bar, quarters);
            if (shares == null) {
                continue;
            }
            BigDecimal bookValuePerShare = equity.divide(shares, SCALE, RoundingMode.HALF_UP);
            if (bookValuePerShare.signum() <= 0) {
                continue;
            }
            points.add(new IndicatorPoint(
                    bar.tradeDate(),
                    bar.close().divide(bookValuePerShare, SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
