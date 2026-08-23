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
 * 3년간 이익소각한 주식 수를 발행주식총수로 나눈다. <b>둘 다 보통주 행</b>이다.
 *
 * <p>IMPORTANT — 소스의 {@code profit_incnr}는 <b>상장 이래 누적</b>이다(Ⅲ. 현재까지 감소한
 * 총수의 내역). 그대로 한 해치로 읽으면 <b>상장 이래 전량이 그 해에 소각된 것</b>으로 나온다.
 * 그래서 세 해 전과의 <b>차분</b>을 내고, 끝점 둘이 다 없으면 값을 내지 않는다.
 *
 * <p>MUST NOT: 감자({@code redc})를 여기 합치지 않는다. <b>무상감자는 주주환원이 아니라
 * 결손보전</b>이고, 주식병합형이면 발행주식수를 크게 줄여 이 값을 대량으로 만든다 — 섞으면
 * 부실 기업과 소각 기업이 같은 칸에 앉는다. 실측에서 {@code redc}는 31행 중 28이 {@code -}라
 * 신호도 거의 없다(§16-A).
 *
 * <p>방향을 주지 않는다. 소각이 희석의 반대라는 것은 정의상 참이지만 <b>그것이 주가에 유리한지는
 * 이 저장소가 재본 적이 없고</b>, {@code SHARES_GROWTH_3Y}와 축이 같다는 것은 유추일 뿐이다.
 */
@Component
@IndicatorMeta(
        name = "3년 이익소각 비율",
        description = "최근 3개 회계연도에 이익소각한 주식 수를 발행주식총수로 나눈 값. "
                + "소스가 누적으로 주므로 두 해의 차분으로 낸다. 무상감자는 세지 않는다.",
        interpretation = "값이 크면 그 기간에 자사주를 실제로 소각해 주식수를 줄였다는 뜻이다. "
                + "그것이 주가에 유리한지는 방향을 재본 적이 없다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        sortOrder = 403)
public class ProfitRetirement3yCalculator implements IndicatorCalculator {

    public static final String CODE = "PROFIT_RETIREMENT_3Y";

    /** 차분의 폭. 끝점 둘이 필요하므로 적재는 넉 해를 받는다. */
    static final int YEARS = 3;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.governance(1, 0, YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.ISSUED_SHARES_COMMON,
                (figures, bar) -> GovernanceMath.percent(
                        GovernanceMath.difference(context, figures,
                                GovernanceMetric.PROFIT_RETIRED_CUM, YEARS, bar.tradeDate()),
                        GovernanceMath.valueOf(figures, GovernanceMetric.ISSUED_SHARES_COMMON)));
    }
}
