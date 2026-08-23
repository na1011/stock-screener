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
 * 소액주주 지분율. 소액주주 보유 주식수를 총발행주식수로 나눈다.
 *
 * <p>분자와 분모가 <b>같은 응답의 같은 행</b>에서 온다(§16-C). 그래서 최대주주 지분율과 달리
 * 표를 가로지르지 않고, 기준일이 갈릴 여지가 없다.
 *
 * <p>린치 §4.2가 <i>「기관 보유 비중은 낮을수록 좋다」</i>를 말하고 원문이 한국주는 별도 조회로
 * 열어 두었지만, <b>소액주주가 곧 비기관은 아니다</b>(지분 1% 미만 주주가 기준이다). 그 대응이
 * 성립하는지 재본 적이 없어 <b>방향을 주지 않는다.</b>
 */
@Component
@IndicatorMeta(
        name = "소액주주 지분율",
        description = "사업보고서의 소액주주 보유 주식수를 총발행주식수로 나눈 값. 분자와 분모가 "
                + "같은 행에서 온다. 소스가 준 보유 비율을 쓰지 않는다(spec §7).",
        interpretation = "높을수록 지분이 널리 퍼져 있다. 린치는 기관 보유가 낮은 쪽을 선호했지만 "
                + "소액주주가 곧 비기관은 아니어서 그 대응을 재본 적이 없다 — 방향을 주지 않는다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        sortOrder = 421)
public class MinorityHolderStakeCalculator implements IndicatorCalculator {

    public static final String CODE = "MINORITY_HOLDER_STAKE";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.governance(1, 0, 2);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.MINORITY_TOTAL_SHARES,
                (figures, bar) -> GovernanceMath.percent(
                        GovernanceMath.valueOf(figures, GovernanceMetric.MINORITY_SHARES),
                        GovernanceMath.valueOf(figures, GovernanceMetric.MINORITY_TOTAL_SHARES)));
    }
}
