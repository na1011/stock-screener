package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** OBV·가격 다이버전스. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "OBV·가격 다이버전스",
        description = "1 강세 다이버전스(가격↓ OBV↑) · −1 약세 다이버전스 · 2 정합 · 0 판정 불가.",
        interpretation = "1은 가격이 눌리는데 거래량은 쌓이는 상태로 반등의 실마리로 읽는다. "
                + "−1은 그 반대다. 2는 둘이 같은 방향으로 뚜렷하다는 뜻이며, "
                + "숫자는 분류일 뿐이라 2가 1보다 좋다는 뜻이 아니다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 246)
public class IchimokuDivergenceCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_DIVERGENCE";

    public IchimokuDivergenceCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.divergence());
    }
}
