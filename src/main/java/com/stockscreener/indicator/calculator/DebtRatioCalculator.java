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
 * 부채비율 = 부채총계 ÷ 자기자본 × 100.
 *
 * <p>둘 다 저량(stock) 항목이라 가장 최근 분기의 잔액을 그대로 쓴다. TTM 합산 대상이 아니다.
 *
 * <p>부채총계가 비어 있으면 {@link FinancialMath#totalLiabilitiesOf}가 자산총계 − 자기자본으로
 * 유도한다. 유도조차 불가능하면 값을 내지 않는다 — 0으로 두면 부채가 없는 우량기업으로 둔갑한다.
 *
 * <p><b>그 유도는 여기 인라인으로 있었고, 그래서 알트만 둘과 NCAV는 같은 재료로도 값을 못
 * 냈다.</b> 한 종목에서 이 카드만 값이 나가고 나머지 셋이 비는데 그 이유가 화면에 없었다.
 * 사본을 다시 만들지 않는다.
 */
@Component
@IndicatorMeta(
        name = "부채비율",
        description = "부채총계를 자기자본으로 나눈 값. 자기자본 대비 빚이 얼마나 되는지를 나타낸다.",
        interpretation = "낮을수록 재무구조가 안정적이다. 통상 100% 이하를 양호하게 보지만 "
                + "금융업이나 설비투자가 큰 업종은 구조적으로 높아 같은 업종끼리 비교해야 한다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 2,
        defaultColumn = true,
        sortOrder = 40,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 200),
                @IndicatorMeta.Band(label = "높음", from = 100, to = 200),
                // 100%는 interpretation이 이미 "양호하게 보는" 경계로 적어 둔 값이다.
                @IndicatorMeta.Band(label = "양호", from = 50, to = 100),
                @IndicatorMeta.Band(label = "매우 낮음", to = 50)
        }
)
public class DebtRatioCalculator implements IndicatorCalculator {

    public static final String CODE = "DEBT_RATIO";

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
        return FinancialMath.byPublication(context, DebtRatioCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        if (quarters.isEmpty()) {
            return null;
        }
        FinancialFigures latest = quarters.get(0);

        BigDecimal equity = latest.totalEquity();
        if (equity == null || equity.signum() <= 0) {
            return null;
        }

        BigDecimal liabilities = FinancialMath.totalLiabilitiesOf(latest);
        if (liabilities == null) {
            return null;
        }
        return FinancialMath.percentage(liabilities, equity);
    }
}
