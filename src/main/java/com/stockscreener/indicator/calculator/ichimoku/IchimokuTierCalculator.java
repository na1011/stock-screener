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
        description = "주봉 일목균형표의 13개 세부 항목과 파동 분석, 일목 밖의 신호를 모두 합산해 매긴 등급. "
                + "1 최우선(강한 강세가 추세 시작 직후에 잡힘) · 2 적격 · 3 관찰 · 4 제외.",
        interpretation = "1이 가장 유리하다. 4는 약세·중립·추세 부재를 전부 담으므로 4가 많은 것이 정상이다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "등급",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        sortOrder = 140,
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

    /** D5에 기대므로 지수가 없는 일자에는 값을 내지 않는다. */
    @Override
    protected boolean needsMarketIndex() {
        return true;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.tier());
    }
}
