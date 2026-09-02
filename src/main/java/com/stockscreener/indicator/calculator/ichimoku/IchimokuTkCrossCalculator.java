package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 주봉 전환선이 기준선 위인가 — TradingView 스크리너의 {@code Conversion above Base}.
 *
 * <p>{@code ICHI_MOD_H}가 같은 부등호를 품고 있지만 그쪽은 <b>조건의 좌변으로 쓸 수 없다.</b>
 * 구름 위치를 함께 접어 넣으면서 "골든크로스 자체가 없음"에 −2를 두었고, 그 −2가 유리한 쪽
 * 극단에 앉아 부등호를 뒤집는다(ADR-0046 §결과). 이 지표는 0과 1뿐이라 그 문제가 없다.
 *
 * <p>부등호의 정본은 {@code WeeklyAnalyzer}의 {@code tkAbove} 하나다 — 여기서 다시 계산하지
 * 않는다.
 */
@Component
@IndicatorMeta(
        name = "일목 전환선 > 기준선",
        description = "주봉 일목균형표의 전환선(9주)이 기준선(26주)보다 위에 있으면 1, 아니면 0이다. "
                + "흔히 일목 골든크로스라 부르는 상태다.",
        interpretation = "1이 유리하다 — 단기 흐름이 중기 흐름보다 강하다는 뜻이다. "
                + "구름 위에서 성립할 때를 더 무겁게 보고, 구름 아래에서는 반등 시도로 읽는다.",
        category = IndicatorCategory.DERIVED,
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 310)
public class IchimokuTkCrossCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_TK_CROSS";

    public IchimokuTkCrossCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.tkCross());
    }
}
