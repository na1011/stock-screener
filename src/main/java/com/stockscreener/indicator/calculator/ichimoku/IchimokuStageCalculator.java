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
        description = "추세가 어느 국면에 있는지. 1 신생 · 2 진행 · 3 중기 · 4 성숙 · 5 추세 부재 · "
                + "6 추세 성숙기이며, 13개 세부 항목의 배점이 전부 이 단계에 따라 달라진다.",
        interpretation = "1~4는 추세가 익어 가는 순서지만 5·6은 그 연장이 아니라 별개 상태라, "
                + "숫자의 크고 작음으로 비교할 수 없다.",
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
