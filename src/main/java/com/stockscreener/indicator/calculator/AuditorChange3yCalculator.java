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
 * 최근 3개 회계연도에 감사인이 바뀐 횟수. 세 해가 <b>다 있어야</b> 값을 낸다.
 *
 * <p>한 회계연도에 행이 둘이라(연결·별도로 보이나 필드로 구분되지 않는다) 유도 단계가
 * {@code adtor}를 <b>집합으로 접어</b> 전년과 견준다 — 한 해의 두 행이 다르다고 교체로 세지 않는다.
 *
 * <p>IMPORTANT — <b>방향을 주지 않는다.</b> 원본 명세의 G-04(감사인 교체)가 적신호로 두었지만
 * 국내에는 <b>주기적 지정제가 교체를 강제한다</b> — 그 제도 아래에서 교체는 회사의 선택이 아니라
 * 규제의 산물이고, 둘을 이 데이터로는 가를 수 없다.
 */
@Component
@IndicatorMeta(
        name = "3년 감사인 교체 횟수",
        description = "최근 3개 회계연도에 외부감사인이 바뀐 횟수. 한 해에 오는 두 행은 집합으로 "
                + "접어 견준다. 세 해가 다 있어야 값을 낸다.",
        interpretation = "값이 크면 감사인이 자주 바뀌었다는 뜻이다. 국내는 주기적 지정제가 교체를 "
                + "강제하므로 회사의 선택인지 규제의 산물인지 이 데이터로는 가를 수 없다 — "
                + "방향을 주지 않는다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.COUNT,
        unit = "건",
        decimalScale = 0,
        sortOrder = 432)
public class AuditorChange3yCalculator implements IndicatorCalculator {

    public static final String CODE = "AUDITOR_CHANGE_3Y";

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
        return GovernanceMath.overBars(context, GovernanceMetric.AUDITOR_CHANGED,
                (figures, bar) -> GovernanceMath.sumOfYears(context, figures,
                        GovernanceMetric.AUDITOR_CHANGED, YEARS, bar.tradeDate()));
    }
}
