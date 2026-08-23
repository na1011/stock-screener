package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
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
 * 이사·감사 전체의 보수 총액을 <b>같은 회계연도의</b> 당기순이익으로 나눈다.
 *
 * <p>소스가 1인 평균 보수를 함께 주지만 <b>쓰지 않는다</b>(spec §7). 담는 것은 총액이고
 * 나눗셈은 우리가 한다.
 *
 * <p>방향을 주지 않는다. 보수가 이익에 비해 크면 대리인 비용의 신호일 수 있지만, 이익이 급감한
 * 해에도 같은 모양이 나오고 <b>둘을 가르는 기준을 이 저장소가 재본 적이 없다.</b>
 * 순이익이 0 이하인 해에는 값을 내지 않는다 — 활성 유니버스 최신 FY 340행 중 67이 적자이고,
 * 안 막으면 그 종목들이 「보수가 아주 적다」로 정렬 위쪽에 앉는다.
 */
@Component
@IndicatorMeta(
        name = "임원보수 대 순이익",
        description = "사업보고서의 이사·감사 전체 보수 지급 총액을 같은 회계연도의 당기순이익으로 "
                + "나눈 값. 소스가 준 1인 평균 보수를 쓰지 않는다(spec §7).",
        interpretation = "높을수록 벌어들인 이익 대비 경영진 보수가 크다. 다만 이익이 급감한 해에도 "
                + "같은 모양이 나오므로 방향을 주지 않는다. 순이익이 0 이하면 값을 내지 않는다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 2,
        sortOrder = 404)
public class ExecPayToNetIncomeCalculator implements IndicatorCalculator {

    public static final String CODE = "EXEC_PAY_TO_NET_INCOME";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return DataRequirement.governance(1, 1, 2);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.EXEC_PAY_TOTAL,
                (figures, bar) -> {
                    FinancialFigures annual =
                            GovernanceMath.annualOf(context, figures.fiscalYear());
                    if (annual == null) {
                        return null;
                    }
                    return GovernanceMath.percent(
                            GovernanceMath.valueOf(figures, GovernanceMetric.EXEC_PAY_TOTAL),
                            annual.netIncome());
                });
    }
}
