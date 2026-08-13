package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 주봉 OBV t-통계량. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "주봉 OBV t-통계량",
        description = "13주 OBV 회귀 기울기의 t-통계량. ±1.96이 유의성 임계다.",
        interpretation = "양수면 매집, 음수면 분산 쪽이다. 절대값이 1.96 미만이면 방향을 말할 수 없다. 절대 수준이 아니라 기울기의 유의성만 보므로 OBV 시작점과 무관하다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 245)
public class IchimokuObvTCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_OBV_T";

    public IchimokuObvTCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return BigDecimal.valueOf(analysis.obvT());
    }
}
