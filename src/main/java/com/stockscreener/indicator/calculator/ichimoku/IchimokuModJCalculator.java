package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 선행스팬 B 평탄. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 선행스팬 B 평탄",
        description = "26주 변동폭이 ATR의 0.25배 미만이면 1(평탄), 0.5배 미만이면 0, 그 이상이면 −1이다.",
        interpretation = "MUST NOT: 방향을 하나로 읽지 않는다 — 신생 단계에서는 평탄이 불리하고 중기·성숙에서는 평탄이 유리하다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 118)
public class IchimokuModJCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_J";

    public IchimokuModJCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modJ());
    }
}
