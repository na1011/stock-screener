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
        description = "2 강한 합치 · 1 약한 합치 · 0 합치 부재 · −1 비활성(주봉 호전이 2 미만).",
        interpretation = "원본의 수식자 i와 같은 값이다. MUST NOT: 조건의 좌변으로 쓰지 않는다 — 「해당 없음」을 뜻하는 센티널이 유리한 쪽 극단에 앉아 있어 부등호를 걸면 정반대 집합이 걸린다. −1은 합치가 나쁜 것이 아니라 판정 자체가 활성화되지 않았다는 뜻이다.",
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
