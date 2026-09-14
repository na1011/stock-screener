package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 총이익성 = (매출 − 매출원가) ÷ 총자산 × 100. Novy-Marx(2013)의 원식이다.
 *
 * <p><b>분모가 매출이 아니라 총자산</b>이라는 것이 매출총이익률과 다른 점이고, 그것이
 * 이 지표를 따로 두는 이유다. 마진이 얇아도 자산을 빨리 굴리는 회사가 여기서 올라온다.
 *
 * <p>한국 시장에서 수익성 이상현상 전반의 재현율은 낮은데(148개 중 5%) 총이익성은 별개
 * 연구에서 유의하게 나왔다 — 프리셋의 수익성 축이 {@code ROE}가 아니라 이 값에 기대는
 * 근거다(ADR-0061).
 *
 * <p>공식과 함정은 {@link GrossProfit}에 있다. 매출총이익은 <b>같은 회계연도의 총자산</b>으로
 * 나눈다 — MUST NOT: 분모를 최근 <b>분기</b> 총자산으로 바꾸지 않는다. 분자가 연간이라
 * 기저가 어긋나고 값은 그럴듯하게 나온다.
 */
@Component
@IndicatorMeta(
        name = "총이익성(GP/A)",
        description = "최근 회계연도의 매출총이익을 총자산으로 나눈 값. "
                + "자산을 얼마나 효율적으로 굴려 원가 위의 이익을 만들었는지를 나타낸다. "
                + "매출원가를 따로 표시하지 않는 업종에서는 값이 나오지 않는다. "
                + "연간 재무만 쓰므로 1년에 한 번 바뀐다.",
        interpretation = "높을수록 같은 자산으로 더 많은 총이익을 만들었다는 뜻이다. "
                + "ROE와 달리 부채를 많이 써도 높아지지 않고, 마진이 얇아도 자산 회전이 "
                + "빠르면 높게 나온다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 52
)
public class GrossProfitToAssetsCalculator implements IndicatorCalculator {

    public static final String CODE = "GP_TO_ASSETS";

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
        return FinancialMath.byPublication(context, GrossProfitToAssetsCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        FinancialFigures latest = GrossProfit.latestAnnual(context.annualFigures());
        BigDecimal grossProfit = GrossProfit.of(context.annualFigures());
        if (grossProfit == null) {
            return null;
        }
        BigDecimal assets = latest.totalAssets();
        if (assets == null || assets.signum() <= 0) {
            return null;
        }
        return FinancialMath.percentage(grossProfit, assets);
    }
}
