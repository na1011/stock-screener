package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 주봉 호전 분류. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "주봉 호전 분류",
        description = "구름 상방·전환선 골든크로스·26주 전 종가 상회 중 몇 개를 채웠는지. 0~3이다.",
        interpretation = "3이 3역호전이다. 이 값이 2 이상이어야 일봉 합치가 활성화된다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "개",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 240)
public class IchimokuKaizenWeeklyCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_KAIZEN_W";

    public IchimokuKaizenWeeklyCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.kaizenWeekly());
    }
}
