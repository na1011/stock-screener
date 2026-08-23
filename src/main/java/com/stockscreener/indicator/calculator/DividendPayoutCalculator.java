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
 * 현금배당성향. 현금배당 총액을 <b>같은 회계연도의</b> 당기순이익으로 나눈다.
 *
 * <p>소스가 {@code (연결)현금배당성향(%)}을 이미 계산해 주지만 <b>쓰지 않는다</b> —
 * spec §7이 외부가 계산한 값에 의존하는 것을 MUST로 금지한다. 분자는 소스가 준 원자료이고
 * 분모는 우리가 이미 저장한 재무다.
 *
 * <p>IMPORTANT — 소스는 배당 총액을 <b>백만원</b>으로 준다({@code 현금배당금총액(백만원)}).
 * 단위가 항목명 문자열 안에만 있고 필드로 오지 않아, 유도 단계에서 원으로 맞춰 담는다.
 * 안 맞추면 이 값이 100만분의 1이 되고 <b>작아서 눈에 덜 띈다.</b>
 *
 * <p>방향을 주지 않는다. 고성장 기업은 배당을 안 하고 재투자하는 편이 낫다는 것이 린치의
 * 주장이고, 어느 쪽이 이 유니버스에서 유리한지 <b>이 저장소가 재본 적이 없다.</b>
 */
@Component
@IndicatorMeta(
        name = "현금배당성향",
        description = "사업보고서의 현금배당 총액을 같은 회계연도의 당기순이익으로 나눈 값. "
                + "소스가 준 배당성향을 쓰지 않고 자체 계산한다(spec §7).",
        interpretation = "높을수록 번 돈의 큰 몫을 배당으로 돌려준다. 어느 쪽이 유리한지는 "
                + "방향을 재본 적이 없다 — 고성장 기업은 재투자가 나을 수 있다. "
                + "순이익이 0 이하인 해에는 값을 내지 않는다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        decimalScale = 1,
        sortOrder = 401)
public class DividendPayoutCalculator implements IndicatorCalculator {

    public static final String CODE = "DIVIDEND_PAYOUT";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST: financialYears가 0이면 안 된다. 재무를 한 행도 못 읽은 종목에서
        // inputsWereReadable이 true가 되어 **옛 행이 지워진다**(ADR-0080·0045).
        return DataRequirement.governance(1, 1, 3);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return GovernanceMath.overBars(context, GovernanceMetric.CASH_DIVIDEND_TOTAL,
                (figures, bar) -> {
                    FinancialFigures annual =
                            GovernanceMath.annualOf(context, figures.fiscalYear());
                    if (annual == null) {
                        return null;
                    }
                    return GovernanceMath.percent(
                            GovernanceMath.valueOf(figures, GovernanceMetric.CASH_DIVIDEND_TOTAL),
                            annual.netIncome());
                });
    }
}
