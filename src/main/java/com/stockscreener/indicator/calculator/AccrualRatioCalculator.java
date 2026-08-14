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
 * 발생액 비율 = (순이익 − 영업활동현금흐름) ÷ 총자산 × 100. 전부 <b>같은 회계연도(FY)</b>다.
 *
 * <p>장부상 이익 중 현금이 따라오지 않은 몫이다. Sloan(1996)의 발생액 이상현상과 Piotroski
 * F-Score의 네 번째 항목(영업현금흐름 &gt; 순이익)이 재는 것이 같다 — <b>이익의 질</b>이다.
 *
 * <p>IMPORTANT — <b>연간만 쓴다.</b> 분기 영업활동현금흐름은 3개월치인지 누적인지 미측정이라
 * 저장하지 않기로 했고(ADR-0056), 실제로 분기 행은 전부 비어 있다. 그래서 이 값은
 * <b>연 1회만 갱신되고 최대 8개월까지 낡는다</b> — 같은 프리셋에 함께 걸리는 {@code ROA}는
 * 분기 TTM이라 기저가 다르다. 둘을 같은 시점의 값으로 읽으면 안 된다.
 *
 * <p>MUST NOT: 분모를 최근 <b>분기</b> 총자산으로 바꾸지 않는다. 분자가 연간이라 기저가
 * 어긋나고, 값은 그럴듯하게 나온다.
 */
@Component
@IndicatorMeta(
        name = "발생액 비율",
        description = "최근 회계연도의 순이익에서 영업활동현금흐름을 뺀 값을 총자산으로 나눈 것. "
                + "장부상 이익 중 현금이 따라오지 않은 몫이다. 연간 재무만 쓰므로 1년에 한 번 바뀐다.",
        interpretation = "0 이하면 벌어들인 이익보다 실제로 들어온 현금이 많다는 뜻으로, "
                + "이익의 질이 좋은 쪽이다. 크게 양수면 매출채권·재고 같은 장부상 항목이 "
                + "이익을 밀어올렸을 수 있다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 62
)
public class AccrualRatioCalculator implements IndicatorCalculator {

    public static final String CODE = "ACCRUAL_RATIO";

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
        List<FinancialFigures> annual = context.annualFigures();
        if (annual.isEmpty()) {
            return List.of();
        }
        // 읽기가 period_end 내림차순이므로 앞이 더 최근이다.
        FinancialFigures latest = annual.get(0);

        BigDecimal netIncome = latest.netIncome();
        BigDecimal operatingCashFlow = latest.operatingCashFlow();
        BigDecimal assets = latest.totalAssets();
        if (netIncome == null || operatingCashFlow == null
                || assets == null || assets.signum() <= 0) {
            return List.of();
        }

        return FinancialMath.spreadOverTradeDates(context.priceBars(),
                FinancialMath.percentage(netIncome.subtract(operatingCashFlow), assets));
    }
}
