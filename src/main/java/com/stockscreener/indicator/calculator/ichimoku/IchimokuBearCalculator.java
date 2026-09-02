package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 약세 점수 합. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 약세 점수 합",
        description = "13 수식자·파동·외부 시그널의 음수 점수 절대값 합. 0~약 43이다.",
        interpretation = "강세 점수와 함께 본다. 작을수록 약세 근거가 적다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "점",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 109)
public class IchimokuBearCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_BEAR";

    public IchimokuBearCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    /** D5에 기대므로 지수가 없는 일자에는 값을 내지 않는다. */
    @Override
    protected boolean needsMarketIndex() {
        return true;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.bear());
    }
}
