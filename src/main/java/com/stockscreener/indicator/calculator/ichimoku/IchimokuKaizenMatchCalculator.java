package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일봉 호전 합치. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일봉 호전 합치",
        description = "주봉 일목 호전이 2 이상일 때 일봉이 얼마나 따라 붙었는지. 2 강한 합치 · 1 약한 합치 · "
                + "0 합치 부재이며, −1은 주봉 호전이 2에 못 미쳐 판정 자체가 열리지 않은 상태다.",
        interpretation = "합치가 강할수록(2) 주봉 신호를 일봉이 뒷받침한다. "
                + "−1은 합치가 나쁜 것이 아니라 판정이 열리지 않은 것이어서, "
                + "부등호 조건을 걸면 그 종목까지 함께 걸린다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 150)
public class IchimokuKaizenMatchCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_KAIZEN_MATCH";

    public IchimokuKaizenMatchCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.kaizenMatch());
    }
}
