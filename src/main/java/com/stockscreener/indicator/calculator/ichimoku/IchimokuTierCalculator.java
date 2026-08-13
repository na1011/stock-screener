package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 Tier. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 Tier",
        description = "주봉 일목 13 수식자·파동 분석·외부 시그널을 합산해 낸 1~4 등급이다. 1이 가장 우선이고 4는 제외다.",
        interpretation = "1은 강한 강세가 추세 시작 직후에 잡힌 자리, 2는 적격, 3은 관찰이다. 4는 약세·중립·추세 부재를 전부 담으므로 4가 많은 것은 정상이다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "등급",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 100,
        bands = {
                @IndicatorMeta.Band(label = "제외", from = 4),
                @IndicatorMeta.Band(label = "관찰", from = 3, to = 4),
                @IndicatorMeta.Band(label = "적격", from = 2, to = 3),
                @IndicatorMeta.Band(label = "최우선", to = 2)
        })
public class IchimokuTierCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_TIER";

    public IchimokuTierCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.tier());
    }
}
