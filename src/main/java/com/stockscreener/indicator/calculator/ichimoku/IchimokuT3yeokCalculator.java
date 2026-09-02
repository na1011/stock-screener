package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 3역호전 시점. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 3역호전 시점",
        description = "3역호전이 처음 성립한 시점. 0이 이번 주이고 숫자가 클수록 오래된 신호다. "
                + "−1은 130주 안에 성립하지 않았다는 뜻, "
                + "−2는 130주 내내 성립해 시점을 특정할 수 없다는 뜻이다.",
        interpretation = "최근에 성립한 쪽(작은 값)이 유리하다. "
                + "다만 −1·−2는 순서가 아니라 별개 상태라 「N 이하」 조건을 걸면 이 둘이 함께 걸린다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "주",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 152)
public class IchimokuT3yeokCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_T3YEOK";

    public IchimokuT3yeokCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.t3yeok());
    }
}
