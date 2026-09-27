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
 * 순이익률 = {@code 당기순이익(TTM) ÷ 매출(TTM) × 100}.
 *
 * <p>버핏 문서 1.4(소비자 독점)·3.4(높고 일관된 이익률)가 영업이익률과 <b>함께</b> 요구하는
 * 값이다. 둘을 같이 보는 이유는 영업 밖에서 새는 것을 잡기 위해서다 — 영업이익률이 높은데
 * 순이익률이 낮으면 금융비용이나 영업외손실이 이익을 먹고 있다는 뜻이고, 그 회사는 문서가
 * 말하는 "구조적으로 마진이 높은 프랜차이즈"가 아니다.
 *
 * <p><b>분기 TTM 기준이다</b> — 같은 화면의 매출총이익률({@code GROSS_MARGIN})이 연간 기준이라
 * 기준 시점이 다르다. 매출원가를 분기로 받지 않기 때문이며(ADR-0062), 그 차이는 프리셋
 * {@code description}에 적힌다.
 */
@Component
@IndicatorMeta(
        name = "순이익률",
        description = "최근 4개 분기 당기순이익을 같은 기간 매출로 나눈 비율. "
                + "영업이익률과 달리 금융비용·영업외손익·법인세를 모두 뺀 뒤의 몫이다.",
        interpretation = "높을수록 매출 1원이 실제로 주주 몫으로 남는 비율이 크다. "
                + "영업이익률은 높은데 순이익률이 낮으면 이자비용이나 영업외손실이 이익을 "
                + "먹고 있다는 뜻이다. 업종에 따라 정상 수준이 크게 다르므로 같은 업종끼리 견준다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 53,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 15),
                @IndicatorMeta.Band(label = "높음", from = 10, to = 15),
                @IndicatorMeta.Band(label = "보통", from = 5, to = 10),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 5),
                @IndicatorMeta.Band(label = "적자", to = 0)
        }
)
public class NetMarginCalculator implements IndicatorCalculator {

    public static final String CODE = "NET_MARGIN";

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
        return FinancialMath.byPublication(context, NetMarginCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        FinancialMath.PeriodSeries series = FinancialMath.seriesOf(context);
        BigDecimal netIncome =
                series.ttm(FinancialFigures::netIncome, 0);
        BigDecimal revenue =
                series.ttm(FinancialFigures::revenue, 0);
        if (netIncome == null || revenue == null || revenue.signum() <= 0) {
            return null;
        }
        return FinancialMath.percentage(netIncome, revenue);
    }
}
