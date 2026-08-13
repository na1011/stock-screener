package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 후행스팬 조망. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 후행스팬 조망",
        description = "26주 전 구간에 겹치는 가격대가 하나도 없으면 1(확보), 있으면 0이다.",
        interpretation = "1이면 26주 전 가격대에 걸리는 것이 없어 위쪽이 트여 있다는 뜻이다. "
                + "매물대가 비어 있으면 상승에 저항이 적다고 본다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 225)
public class IchimokuModDCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_D";

    public IchimokuModDCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modD());
    }
}
