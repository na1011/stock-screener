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

import java.math.BigDecimal;
import java.util.List;

/**
 * 3년간 사외이사가 해임되거나 중도퇴임한 수를 이사 수로 나눈다.
 *
 * <p>세 회계연도가 <b>다 있어야</b> 값을 낸다. 부분 합에 {@code _3Y} 이름을 붙이면
 * {@code phase-criteria}의 <i>「지표 이름이 그 값이 실제로 세는 것을 말한다」</i>를 어긴다.
 *
 * <p>소스의 {@code -}는 <b>0명</b>이다 — 표본 여덟에서 해임이 8/8, 중도퇴임이 6/8로 {@code -}인데
 * 이사 수는 다 채워져 있다(dart-governance.md의 「사외이사·소액주주」 절). 결손으로 세면 이 축이 전 종목
 * 판정불가가 된다.
 *
 * <p>방향을 주지 않는다. 잦은 중도퇴임이 이사회 독립성의 훼손일 수도 있고 정상적인 교체일 수도
 * 있는데 <b>둘을 가르는 기준을 이 저장소가 재본 적이 없다.</b>
 */
@Component
@IndicatorMeta(
        name = "3년 사외이사 이탈률",
        description = "최근 3개 회계연도의 사외이사 해임·중도퇴임 수를 이사 수로 나눈 값. "
                + "세 해가 다 있어야 값을 낸다.",
        interpretation = "값이 크면 그 기간에 사외이사가 자주 바뀌었다는 뜻이다. 그것이 독립성의 "
                + "훼손인지 정상적인 교체인지는 방향을 재본 적이 없다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 1,
        sortOrder = 411)
public class DirectorTurnover3yCalculator implements IndicatorCalculator {

    public static final String CODE = "DIRECTOR_TURNOVER_3Y";

    static final int YEARS = 3;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.governance(1, 0, YEARS);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.DIRECTOR_COUNT,
                (figures, bar) -> {
                    BigDecimal dismissed = GovernanceMath.sumOfYears(context, figures,
                            GovernanceMetric.DIRECTOR_DISMISSED, YEARS, bar.tradeDate());
                    BigDecimal resigned = GovernanceMath.sumOfYears(context, figures,
                            GovernanceMetric.DIRECTOR_RESIGNED, YEARS, bar.tradeDate());
                    if (dismissed == null || resigned == null) {
                        return null;
                    }
                    return GovernanceMath.percent(dismissed.add(resigned),
                            GovernanceMath.valueOf(figures, GovernanceMetric.DIRECTOR_COUNT));
                });
    }
}
