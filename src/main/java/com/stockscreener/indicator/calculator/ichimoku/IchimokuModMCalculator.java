package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 미래 구름 트위스트. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 미래 구름 트위스트",
        description = "몇 주 뒤에 구름 색이 뒤집히는지. 1~25주이며 뒤집히지 않으면 −1이다.",
        interpretation = "구름이 뒤집히는 시점이 가까울수록 추세가 흔들릴 수 있다. "
                + "−1은 「뒤집힘이 예정에 없다」는 뜻으로 가장 유리한 상태인데 숫자로는 가장 작아, "
                + "「10 이하」 같은 조건을 걸면 가장 좋은 종목이 함께 걸린다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "주",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        // 원시 수식자다. 조건에 넣으면 「내 조건」 구획에 나온다(ADR-0058).
        detailDefault = false,
        sortOrder = 235)
public class IchimokuModMCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_M";

    public IchimokuModMCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modM());
    }
}
