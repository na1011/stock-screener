package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 큰 기둥. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 큰 기둥",
        description = "일목균형표 13개 세부 항목의 양수 점수 합에서 음수 점수 합을 뺀 값. −35~+35이다.",
        interpretation = "클수록 일목 자체의 강세 근거가 두껍다. "
                + "거래량·상대강도를 뺀 순수 일목 신호라 외부 시그널과 나눠 본다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "점",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 144)
public class IchimokuPillarCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_PILLAR";

    public IchimokuPillarCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.pillar());
    }
}
