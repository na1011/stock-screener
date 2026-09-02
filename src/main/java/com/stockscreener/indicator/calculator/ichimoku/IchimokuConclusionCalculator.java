package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 방향 결론. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 방향 결론",
        description = "1 강세 · −1 약세 · 0 중립. 신호 강도의 절대값이 결론 임계(5) 미만이면 0이다.",
        interpretation = "Tier 1~3은 전부 이 값이 1인 종목이다. 0은 방향을 말할 수 없다는 뜻이지 중립적 매력이 아니다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 107)
public class IchimokuConclusionCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_CONCLUSION";

    public IchimokuConclusionCalculator(IchimokuAnalyses analyses) {
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
        return of(analysis.conclusion());
    }
}
