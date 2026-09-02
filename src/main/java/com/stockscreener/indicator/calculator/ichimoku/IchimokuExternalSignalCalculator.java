package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 외부 시그널 합. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 외부 시그널 합",
        description = "거래량·다이버전스 점수(D2)와 26주 초과 수익률 점수(D5)의 합. −6~+6이다.",
        interpretation = "일목 자체 신호 밖의 정합도다. 같은 Tier 안 3차 기준이며 클수록 외부 정합이 강하다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "점",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 143)
public class IchimokuExternalSignalCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_EXT_SIGNAL";

    public IchimokuExternalSignalCalculator(IchimokuAnalyses analyses) {
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
        return of(analysis.extSignal());
    }
}
