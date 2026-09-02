package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 추세 신선도. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 추세 신선도",
        description = "3역호전이 처음 성립한 뒤 지난 주 수. 0~128이다.",
        interpretation = "작을수록 추세 시작 직후라는 뜻이고 같은 Tier 안 2차 기준이다. MUST NOT: 조건의 좌변으로 쓰지 않는다 — 「해당 없음」을 뜻하는 센티널이 유리한 쪽 극단에 앉아 있어 부등호를 걸면 정반대 집합이 걸린다. −1은 3역호전 미성립 또는 추세 성숙기라 「신선도 5 이하」를 걸면 그것까지 통과한다.",
        category = IndicatorCategory.TECHNICAL,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        unit = "주",
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 142)
public class IchimokuFreshnessCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_FRESHNESS";

    public IchimokuFreshnessCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.freshness());
    }
}
