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
        sortOrder = 20
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

        FinancialFigures latest = quarters.get(0);
        BigDecimal equity = latest.totalEquity();
        BigDecimal shares = latest.sharesOutstanding();
        if (equity == null || equity.signum() <= 0 || shares == null || shares.signum() <= 0) {
            return List.of();
        }

        BigDecimal bookValuePerShare = equity.divide(shares, SCALE, RoundingMode.HALF_UP);

        List<IndicatorPoint> points = new ArrayList<>(context.priceBars().size());
        for (PriceBar bar : context.priceBars()) {
            points.add(new IndicatorPoint(
                    bar.tradeDate(),
                    bar.close().divide(bookValuePerShare, SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
