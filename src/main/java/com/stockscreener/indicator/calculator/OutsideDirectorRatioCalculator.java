package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 사외이사 비율. 독립(사외)이사 수를 이사 수로 나눈다.
 *
 * <p>거버넌스 지표 중 <b>근거가 가장 단단한 항목</b>이다. Black·Jang·Kim(2006) <i>JLEO</i> 22(2)가
 * 한국 상장사에서 거버넌스 지수가 기업가치를 예측함을 보이고, 그중 <b>사외이사 50%가 Tobin's q를
 * 0.13 올린다</b>는 인과 추정을 낸다. 신흥시장 이사회 독립성의 인과 증거이며 이 유니버스가 그 표본이다.
 *
 * <p>구간 라벨의 50%가 그 논문의 문턱이다. <b>임계를 프리셋 조건으로 걸지는 않는다</b> —
 * 그것은 통과율·겹침·순위상관을 다시 재야 하는 결정이다(ADR-0068·0070).
 */
@Component
@IndicatorMeta(
        name = "사외이사 비율",
        description = "사업보고서의 독립(사외)이사 수를 이사 수로 나눈 값.",
        interpretation = "높을수록 이사회가 경영진에서 독립적이다. Black·Jang·Kim(2006)이 한국 "
                + "상장사에서 사외이사 50%가 Tobin's q를 0.13 올린다는 인과 추정을 냈다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 1,
        sortOrder = 410,
        bands = {
                @IndicatorMeta.Band(label = "과반(문헌 문턱)", from = 50),
                @IndicatorMeta.Band(label = "3분의 1 이상", from = 33.3, to = 50),
                @IndicatorMeta.Band(label = "3분의 1 미만", to = 33.3)
        })
public class OutsideDirectorRatioCalculator implements IndicatorCalculator {

    public static final String CODE = "OUTSIDE_DIRECTOR_RATIO";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.governance(1, 0, 3);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.DIRECTOR_COUNT,
                (figures, bar) -> GovernanceMath.percent(
                        GovernanceMath.valueOf(figures, GovernanceMetric.OUTSIDE_DIRECTOR_COUNT),
                        GovernanceMath.valueOf(figures, GovernanceMetric.DIRECTOR_COUNT)));
    }
}
