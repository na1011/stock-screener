package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * PER 5년 중앙값 대비 할인율. 시간축과 함정은 {@link ValuationDiscount}가 정본이다.
 *
 * <p>셋 중 뜻이 가장 곧지만 <b>커버리지가 가장 좁다</b> — 적자 구간이 표본에서 빠져 5년 중앙값 자체가 안 나오는 종목이 많다. 그 자리를 PBR·PSR 판이 메운다.
 */
@Component
@IndicatorMeta(
        name = "PER 5년 중앙값 대비 할인율",
        description = "현재 PER 배수가 이 종목의 5년 중앙값보다 몇 % 아래인지. 양수면 자기 과거보다 싸다.",
        interpretation = "클수록 이 회사 기준으로 싼 구간이다. 업종 평균이 아니라 자기 과거와 "
                + "견주므로 업종이 특이한 종목에도 쓸 수 있고, 국면이 바뀌어도 뜻이 유지된다. "
                + "다만 싸진 이유가 사업이 나빠져서일 수 있으므로 수익성·재무 지표와 함께 읽는다 "
                + "— 이 값 하나로는 저평가와 가치함정이 갈리지 않는다. "
                + "최신 거래일 기준 값이며 과거 날짜의 행도 같은 값이다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 1,
        sortOrder = 13,
        bands = {
                @IndicatorMeta.Band(label = "크게 낮다", from = 30),
                @IndicatorMeta.Band(label = "낮다", from = 10, to = 30),
                @IndicatorMeta.Band(label = "비슷하다", from = -10, to = 10),
                @IndicatorMeta.Band(label = "높다", to = -10)
        }
)
public class PerDiscount5yCalculator implements IndicatorCalculator {

    public static final String CODE = "PER_DISCOUNT_5Y";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // priceBarsNeeded = 0이 맞다. 실효 워밍업은 「자기 몫 + max(부모)」라 부모의 1,261을
        // 물려받으므로 ADR-0055의 미보정 기업행위 억제가 살아 있고, 읽기 창 최댓값도 안 민다.
        //
        // MUST NOT: financialYears를 0으로 두지 않는다. ADR-0080이 「입력을 못 읽은 것」과
        // 「값이 없는 것」을 DataRequirement.needsFinancial로만 가르므로, 0이면 재무가 한 행도
        // 없는 종목에서 부모 행은 남는데 이 지표 행만 지워진다.
        return new DataRequirement(0, EpsCagr5yCalculator.YEARS + 1,
                Set.of(PerCalculator.CODE, PerMedian5yCalculator.CODE));
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return ValuationDiscount.of(context, PerCalculator.CODE, PerMedian5yCalculator.CODE);
    }
}
