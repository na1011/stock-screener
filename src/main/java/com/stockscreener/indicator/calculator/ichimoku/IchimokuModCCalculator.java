package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 구름 두께. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 구름 두께",
        description = "정규화 두께가 130주 중앙값보다 크면 1(두꺼움), 아니면 0(얇음)이다.",
        interpretation = "구름이 두꺼우면 지지·저항이 단단하고 얇으면 쉽게 뚫린다. "
                + "추세가 막 시작할 때는 얇은 쪽이, 자리를 잡은 뒤에는 두꺼운 쪽이 유리해 "
                + "한 방향으로만 읽을 수 없다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        // 원시 수식자다. 조건에 넣으면 「내 조건」 구획에 나온다(ADR-0058).
        detailDefault = false,
        sortOrder = 222)
public class IchimokuModCCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_C";

    public IchimokuModCCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modC());
    }
}
