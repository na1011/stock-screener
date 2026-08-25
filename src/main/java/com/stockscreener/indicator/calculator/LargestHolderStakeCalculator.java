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
 * 최대주주와 특수관계인의 지분율. 소스가 준 지분율을 쓰지 않고 주식수를 나눈다(spec §7).
 *
 * <p>IMPORTANT — <b>표를 가로지르는 나눗셈이다.</b> 분자는 최대주주 표, 분모는 주식 총수 표와
 * 소액주주 표에서 온다. 그래서 {@code GovernanceMath.agreedTotalShares}가 두 분모 후보를 대조하고
 * <b>어긋나면 값을 내지 않는다</b> — 소스가 발행주식수를 10<sup>6</sup>배로 낸 회사가 실재하고
 * (229640, dart-governance.md의 「최대주주」 절) 한쪽만 쓰면 그 종목의 지분율이 0.00006%가 되는데 값이 작아
 * 눈에 안 띈다.
 *
 * <p>분자는 {@code nm}이 {@code 계}인 합계 행을 <b>빼고</b> 더한 것이다. 판정은 완전일치이며,
 * {@code contains("계")}로 하면 {@code 삼성생명보험㈜ (특별계정)} 같은 주주가 통째로 빠진다.
 *
 * <p>방향을 주지 않는다. 높은 지분율은 경영권 안정이기도 하고 소수주주 이익과의 충돌이기도 한데
 * <b>어느 쪽이 지배적인지 이 저장소가 재본 적이 없다.</b>
 */
@Component
@IndicatorMeta(
        name = "최대주주 지분율",
        description = "사업보고서의 최대주주·특수관계인 기말 소유 주식수를 발행주식총수로 나눈 값. "
                + "소스가 준 지분율을 쓰지 않고 자체 계산한다(spec §7).",
        interpretation = "높으면 경영권이 안정적이지만 소수주주 이익과 충돌할 여지도 커진다. "
                + "어느 쪽이 지배적인지 재본 적이 없어 방향을 주지 않는다. "
                + "두 표가 말하는 발행주식총수가 5% 넘게 어긋나면 값을 내지 않는다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        sortOrder = 420)
public class LargestHolderStakeCalculator implements IndicatorCalculator {

    public static final String CODE = "LARGEST_HOLDER_STAKE";

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
        return GovernanceMath.overBars(context, GovernanceMetric.LARGEST_HOLDER_SHARES_COMMON,
                (figures, bar) -> GovernanceMath.percent(
                        GovernanceMath.valueOf(figures,
                                GovernanceMetric.LARGEST_HOLDER_SHARES_COMMON),
                        GovernanceMath.agreedTotalShares(figures)));
    }
}
