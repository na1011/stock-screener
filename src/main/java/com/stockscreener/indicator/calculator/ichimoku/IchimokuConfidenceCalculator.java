package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 신뢰도. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 신뢰도",
        description = "1 강한 · 2 중간 · 3 약한. 신호 강도의 절대값이 15·8 임계를 넘는지로 갈린다.",
        interpretation = "작을수록 신호가 뚜렷하다. Tier 판정이 이 값과 추세 단계의 조합이다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 106,
        bands = {
                @IndicatorMeta.Band(label = "약한", from = 3),
                @IndicatorMeta.Band(label = "중간", from = 2, to = 3),
                @IndicatorMeta.Band(label = "강한", to = 2)
        })
public class IchimokuConfidenceCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_CONFIDENCE";

    public IchimokuConfidenceCalculator(IchimokuAnalyses analyses) {
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
        return of(analysis.confidence());
    }
}
