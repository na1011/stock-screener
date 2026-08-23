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
 * 자기주식 비율. 자기주식수를 발행주식총수로 나눈다. <b>둘 다 보통주 행</b>이다.
 *
 * <p>IMPORTANT — <b>방향을 주지 않는다.</b> 국내에서 자사주는 주주환원이기도 하고 경영권 방어
 * 수단이기도 한데 <b>어느 쪽이 지배적인지 이 저장소가 재본 적이 없다.</b> 방향을 주면 화면이
 * 재보지 않은 판정을 그린다. {@code docs/phase-criteria.md} §P18 축④가 이 지표를 이름으로 든다.
 *
 * <p>보통주 행을 쓰는 이유는 {@code price_daily.listed_shares}·{@code MARKET_CAP}이 전부 보통주
 * 축이라 <b>합계를 쓰면 이 지표만 분모가 달라지기</b> 때문이다 — 삼성전자 합계 발행총수가 67.4억인데
 * 보통주만은 59.2억이다(§16-A).
 *
 * <p>분자와 분모가 <b>같은 응답의 같은 행</b>에서 온다. 그래서 소스가 단위를 잘못 낸 회사에서도
 * 비율은 성립한다 — 229640은 두 값이 함께 10<sup>6</sup>배로 왔다(§16-C).
 */
@Component
@IndicatorMeta(
        name = "자기주식 비율",
        description = "사업보고서의 자기주식수를 발행주식총수로 나눈 값. 둘 다 보통주 행이다.",
        interpretation = "국내에서 자사주는 주주환원이기도 경영권 방어이기도 하다. "
                + "어느 쪽이 지배적인지 재본 적이 없어 방향을 주지 않는다 — 값만 보고 해석은 "
                + "그 회사의 소각 이력과 함께 본다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        sortOrder = 402)
public class TreasuryShareRatioCalculator implements IndicatorCalculator {

    public static final String CODE = "TREASURY_SHARE_RATIO";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.governance(1, 0, 4);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.ISSUED_SHARES_COMMON,
                (figures, bar) -> GovernanceMath.percent(
                        GovernanceMath.valueOf(figures, GovernanceMetric.TREASURY_SHARES_COMMON),
                        GovernanceMath.valueOf(figures, GovernanceMetric.ISSUED_SHARES_COMMON)));
    }
}
