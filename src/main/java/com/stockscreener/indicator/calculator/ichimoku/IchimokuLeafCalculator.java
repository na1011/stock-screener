package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 파동 leaf. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 파동 leaf",
        description = "호소다 값폭관측론 판정. 1 진행 중 · 2 실패 · 3 A 대용 · 4 C 미성숙 · 5 첫 목표 도달 · 6 A 이탈 · 8 유보 · 9 초기 단계.",
        interpretation = "MUST NOT: 크기로 비교하지 않는다 — 순서가 아니라 분류다. 신생 단계는 언제나 9이고 그 점수 기여는 0이다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 125)
public class IchimokuLeafCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_LEAF";

    public IchimokuLeafCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.leaf());
    }
}
