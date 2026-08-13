package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 구름 내부 체류. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 구름 내부 체류",
        description = "0 체류 없음 · 1 단기(9주 미만) · 2 장기.",
        interpretation = "단계와 무관하게 체류 없음이 +3, 단기 −2, 장기 −3이다. 구름 안은 방향이 정해지지 않은 자리다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "주",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 223)
public class IchimokuModKCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_K";

    public IchimokuModKCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modK());
    }
}
