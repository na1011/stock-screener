package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 미래 구름 색. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 미래 구름 색",
        description = "26주 뒤에 놓일 구름의 색. 1 녹색 · −1 적색이다.",
        interpretation = "26주 뒤 구름의 색이다. 지금은 음운이어도 미래가 양운이면 추세가 바뀌는 중일 수 있다. "
                + "선행스팬은 이미 확정된 값이라 예측이 아니라 관측이다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        // 원시 수식자다. 조건에 넣으면 「내 조건」 구획에 나온다(ADR-0058).
        detailDefault = false,
        sortOrder = 221)
public class IchimokuModBCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_B";

    public IchimokuModBCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modB());
    }
}
