package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 5년 최저 영업이익률 = {@code min(영업이익 ÷ 매출, 최근 5개 회계연도)}.
 *
 * <p>버핏 문서 3.4의 <b>일관성</b> 조건이다. 그 문서는 <i>"일관성(마진의 시계열 안정성) 조건은
 * 변동성 필터가 없어 구현 불가"</i>라고 적지만 그것은 TradingView의 한계다(ADR-0067).
 *
 * <p><b>표준편차가 아니라 최솟값을 쓴다.</b> 문서 1.3이 프랜차이즈를 <i>"점유율 손실 없이
 * 가격을 올릴 수 있고 큰 실수를 저질러도 살아남을 체력이 있는"</i> 회사로 정의한다 — 그것은
 * 마진이 <b>얼마나 흔들리는가</b>가 아니라 <b>나쁜 해에도 얼마나 남는가</b>이다. 표준편차는
 * 낮은 마진에서 낮게 유지되는 회사에게도 좋은 점수를 준다.
 *
 * <p>{@link OperatingMarginCalculator}(분기 TTM)와 기저가 다르다 — 이쪽은 회계연도다.
 * 최근 마진은 좋은데 이 값이 뚜렷이 낮으면 <b>사이클의 바닥을 지나온 회사</b>라는 뜻이다.
 *
 * <p>MUST NOT: 매출이 0 이하인 해를 나누지 않는다. 매출이 없는 해는 마진이 정의되지 않으며,
 * 절댓값으로 나누면 영업적자가 <b>양수 마진</b>으로 나온다.
 */
@Component
@IndicatorMeta(
        name = "5년 최저 영업이익률",
        description = "최근 5개 회계연도 각각의 영업이익률 가운데 가장 낮은 값. "
                + "다섯 해가 연속으로 있어야 계산하며, 한 해라도 없으면 값을 내지 않는다. "
                + "연간 기준이라 분기 기준인 영업이익률과 기저가 다르다.",
        interpretation = "높을수록 나쁜 해에도 이익률을 지켰다는 뜻이고, 가격 결정력의 흔적이다. "
                + "영업이익률은 높은데 이 값이 뚜렷이 낮으면 최근에 좋아진 것이지 "
                + "줄곧 그랬던 것이 아니다. 음수면 관측 구간에 영업적자인 해가 있었다는 뜻이다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 55,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 15),
                @IndicatorMeta.Band(label = "높음", from = 8, to = 15),
                @IndicatorMeta.Band(label = "보통", from = 3, to = 8),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 3),
                @IndicatorMeta.Band(label = "영업적자 해가 있다", to = 0)
        }
)
public class OperatingMarginMin5yCalculator implements IndicatorCalculator {

    public static final String CODE = "OPM_MIN_5Y";

    static final int YEARS = 5;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.priceAndFinancial(1, YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, OperatingMarginMin5yCalculator::minimumMargin);
    }

    /** @return 다섯 해가 연속하고 모두 값이 있을 때만 최솟값. 아니면 null */
    private static BigDecimal minimumMargin(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (!FinancialMath.isConsecutiveAnnual(annual, YEARS)) {
            return null;
        }
        BigDecimal min = null;
        for (int i = 0; i < YEARS; i++) {
            FinancialFigures figures = annual.get(i);
            BigDecimal revenue = figures.revenue();
            BigDecimal operatingIncome = FinancialMath.operatingIncomeOf(figures);
            if (operatingIncome == null || revenue == null || revenue.signum() <= 0) {
                return null;
            }
            BigDecimal margin = FinancialMath.percentage(operatingIncome, revenue);
            if (min == null || margin.compareTo(min) < 0) {
                min = margin;
            }
        }
        return min;
    }
}
