package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 추세 단계. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 추세 단계",
        description = "1 신생 · 2 진행 · 3 중기 · 4 성숙 · 5 추세 부재 · 6 추세 성숙기.",
        interpretation = "13 수식자의 배점이 전부 이 단계에 달려 있다. MUST NOT: 크기로 비교하지 않는다 — 5·6은 1~4의 연장이 아니라 별개 상태다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 145)
public class IchimokuStageCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_STAGE";

    public IchimokuStageCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.stage());
    }
}
