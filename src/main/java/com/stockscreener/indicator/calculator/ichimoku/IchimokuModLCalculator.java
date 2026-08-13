package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 선행스팬 A 기울기. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 선행스팬 A 기울기",
        description = "13주 회귀 기울기가 ATR의 ±0.25배를 넘는지로 1 상승 · 0 평탄 · −1 하락으로 가른다.",
        interpretation = "선행스팬 A는 전환선과 기준선의 중간값이라 구름의 앞쪽 경계를 만든다. "
                + "상승(1)이면 앞으로 놓일 구름이 위로 올라간다는 뜻이다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 232)
public class IchimokuModLCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_L";

    public IchimokuModLCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modL());
    }
}
