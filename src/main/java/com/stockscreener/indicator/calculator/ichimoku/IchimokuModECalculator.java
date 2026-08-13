package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 일목 시간론 변화일. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "일목 시간론 변화일",
        description = "3역호전 이후 경과 주수가 호소다 기본수치(9·17·26·33·42·52·65·76·129)의 ±1주에 들면 1(도래), 아니면 0이다.",
        interpretation = "변화일은 추세가 꺾일 수 있는 자리로 보므로 아직 오지 않은 편(0)이 낫다. "
                + "−1은 세기 시작할 기준 시점이 없다는 뜻이며 크기가 아니다 — "
                + "이 값에 부등호를 걸면 그 종목까지 함께 걸린다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 236)
public class IchimokuModECalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_MOD_E";

    public IchimokuModECalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.modE());
    }
}
