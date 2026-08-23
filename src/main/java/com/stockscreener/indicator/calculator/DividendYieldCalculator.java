package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.GovernanceMetric;
import com.stockscreener.indicator.GovernanceValue;
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
 * 배당수익률. 주당 현금배당금(보통주)을 그날 종가로 나눈다.
 *
 * <p>Boudoukh·Michaely·Richardson·Roberts(2007) <i>JF</i>가 배당과 자사주를 합한 총주주환원이
 * 배당수익률보다 예측력이 강하고 <b>횡단면에서 priced factor</b>임을 보인다. 둘은 대체재라
 * 한쪽만 세면 틀리므로, 이 저장소는 자사주 쪽을 {@code TREASURY_SHARE_RATIO}·
 * {@code PROFIT_RETIREMENT_3Y}로 따로 두고 <b>합치지 않는다</b> — 자사주 금액을 소스가 주지
 * 않아 합치려면 수량 × 주가가 필요한데 「매입가 ≈ 현재가」를 재본 적이 없다.
 *
 * <p>MUST: 기업 행위를 <b>명시로 걷는다.</b> 주당 배당금은 보고서 시점의 주식수 기준이고 분모는
 * 그날 종가라, 사이에 분할·무상증자가 있으면 배수만큼 틀린 채 그럴듯한 값이 된다 — 같은 병이
 * {@code PBR}·{@code SHARES_GROWTH_3Y}에서 이미 났다(ADR-0051). 실측으로 활성 350 중
 * 2024-12-31 이후 승인된 기업 행위가 13종목 13건이고 쓰기 창 안에 7건이다.
 *
 * <p>결산기준일과 공시일 사이의 사건은 이 보정으로 <b>안 걷힌다</b>. 그 사례는 재본 적이 없다.
 */
@Component
@IndicatorMeta(
        name = "배당수익률",
        description = "사업보고서의 주당 현금배당금(보통주)을 그날 종가로 나눈 값. 소스가 준 "
                + "현금배당수익률을 쓰지 않고 자체 계산한다(spec §7).",
        interpretation = "높을수록 주주에게 현금으로 돌려주는 몫이 크다. 다만 자사주 매입과는 "
                + "대체재라 이 하나만 보면 총주주환원을 과소평가한다 — 자기주식 비율을 함께 본다.",
        category = IndicatorCategory.GOVERNANCE,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 400)
public class DividendYieldCalculator implements IndicatorCalculator {

    public static final String CODE = "DIVIDEND_YIELD";

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
        return GovernanceMath.overBars(context, GovernanceMetric.DPS_COMMON, (figures, bar) -> {
            GovernanceValue dps = figures.get(GovernanceMetric.DPS_COMMON);
            if (dps == null || bar.close() == null || bar.close().signum() <= 0) {
                return null;
            }
            // 공시 시점부터 이 봉까지의 분할·무상증자를 나눈다. 안 나누면 5:1 분할 뒤
            // 배당수익률이 다섯 배로 나온다.
            BigDecimal multiple =
                    context.corporateActionMultiple(dps.publishedAt(), bar.tradeDate());
            BigDecimal adjusted = multiple.signum() > 0
                    ? dps.value().divide(multiple, FinancialMath.SCALE,
                            java.math.RoundingMode.HALF_UP)
                    : dps.value();
            return GovernanceMath.percent(adjusted, bar.close());
        });
    }
}
