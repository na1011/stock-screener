package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일봉 호전 분류. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일봉 호전 분류",
        description = "주봉 호전 분류와 같은 세 일목 조건을 일봉 차원에서 센 값. 0~3이다.",
        interpretation = "많을수록 일봉 흐름이 호전됐다는 뜻이다. 주봉 호전이 함께 성립할 때 의미가 있고, "
                + "일봉 단독으로는 하루 이틀의 움직임에 흔들린다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "개",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        // 원시 수식자다. 조건에 넣으면 「내 조건」 구획에 나온다(ADR-0058).
        detailDefault = false,
        sortOrder = 241)
public class IchimokuKaizenDailyCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_KAIZEN_D";

    public IchimokuKaizenDailyCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.kaizenDaily());
    }
}
