package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 전환·기준 위치. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 전환·기준 위치",
        description = "1 둘 다 구름 위 · 0 하나만 위 · −1 둘 다 구름 아래 · −2 전환선이 기준선 위로 오르지 않았다.",
        interpretation = "1은 전환선과 기준선이 모두 구름 위에 있는 가장 단단한 자리다. "
                + "−2는 크기가 아니라 「전환선이 기준선을 아직 넘지 못했다」는 별개 상태라, "
                + "이 값에 부등호를 걸면 그 종목까지 함께 걸린다. "
                + "전환선과 기준선의 관계만 보려면 「일목 전환선 > 기준선」을 쓴다.",
        category = IndicatorCategory.DERIVED,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 315)
public class IchimokuModHCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_H";

    public IchimokuModHCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modH());
    }
}
