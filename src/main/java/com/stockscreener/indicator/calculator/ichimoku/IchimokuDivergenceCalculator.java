package com.stockscreener.indicator.calculator.ichimoku;

import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** OBV·가격 다이버전스. 값의 정의와 옮긴 근거는 {@link IchimokuAnalysis}와 ADR-0046에 있다. */
@Component
@IndicatorMeta(
        name = "OBV·가격 다이버전스",
        description = "1 강세 다이버전스(가격↓ OBV↑) · −1 약세 다이버전스 · 2 정합 · 0 판정 불가.",
        interpretation = "1은 가격이 눌리는데 거래량은 쌓이는 상태로 반등의 실마리로 읽는다. "
                + "−1은 그 반대다. 2는 둘이 같은 방향으로 뚜렷하다는 뜻이며, "
                + "숫자는 분류일 뿐이라 2가 1보다 좋다는 뜻이 아니다.",
        category = IndicatorCategory.OSCILLATOR,
        // 새 valueType enum을 만들지 않는다 — enum + CHECK 마이그레이션 + wire-contract +
        // 프론트 미러까지 번진다. 프론트는 valueType으로 포맷을 분기하지 않는다.
        valueType = IndicatorValueType.COUNT,
        decimalScale = 0,
        direction = IndicatorMeta.Direction.NEUTRAL,
        sortOrder = 246,
        // 값이 -1/0/1/2인 **순서 없는 분류**다. 이 지표의 interpretation이 스스로
        // "2가 1보다 좋다는 뜻이 아니다"라고 적는데, 구간(bands)은 구조적으로 순서라
        // 붙이면 화면이 2를 1보다 오른쪽에 그려 그 문장이 금지한 읽기를 만든다.
        // 구간 없이 숫자만 두는 것은 frontend.md의 "값만 나열하지 않는다"에 걸리므로
        // 감춘다 — ADR-0058이 ICHI_MOD_* 열셋을 감춘 것과 같은 근거다.
        // 실측으로 구분력도 낮다(308종목 중 두 값에 97%가 몰린다).
        // 조건으로 쓰던 프리셋은 폐기됐지만(ADR-0061) 결과 표와 조건 빌더에는 그대로 나온다.
        detailDefault = false)
public class IchimokuDivergenceCalculator extends IchimokuIndicator {

    public static final String CODE = "ICHI_DIVERGENCE";

    public IchimokuDivergenceCalculator(IchimokuAnalyses analyses) {
        super(analyses);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected BigDecimal valueOf(IchimokuAnalysis analysis) {
        return of(analysis.divergence());
    }
}
