package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 자기 자신의 5년 PBR 중앙값. 구조와 함정은 {@link ValuationHistory}가 정본이다.
 *
 * <p><b>{@code PER_MEDIAN_5Y}보다 커버리지가 넓은 것이 이 지표의 존재 이유다.</b> 자기자본은
 * 적자에도 남아 있어 표본이 잘 안 준다 — {@code PER_MEDIAN_5Y}가 활성 350 중 218인데 그 결손의
 * 대부분이 적자 구간이고, {@code PBR}은 346에 값이 있다.
 *
 * <p>IMPORTANT — <b>자기자본은 저량이다.</b> {@code PER}·{@code PSR}처럼 4분기를 더하면 <b>네 배</b>가
 * 되고 값이 그럴듯해 드러나지 않는다. 그 시점에 공시돼 있던 <b>한 분기의 잔액</b>을 쓴다.
 *
 * <p>IMPORTANT — <b>{@code FinancialFigures.bps}를 쓰지 않는다.</b> 그것은 소스가 준 값이고
 * 지표는 언제나 자체 계산이다(spec §7). 자기자본 ÷ 주식수로 직접 만든다 —
 * {@code PbrCalculator}가 같은 자리에서 같은 판단을 한다.
 */
@Component
@IndicatorMeta(
        name = "5년 PBR 중앙값",
        description = "지난 5년 동안 이 종목의 PBR이 놓였던 자리의 한가운데 값. "
                + "매 거래일의 주가를 그날 공시돼 있던 자기자본으로 나눠 모은 뒤 중앙값을 취한다.",
        interpretation = "지금 PBR을 옆에 두고 읽는 참조값이다. 현재 PBR이 이 값보다 낮으면 "
                + "이 회사 기준으로 싼 구간에 있다는 뜻이다 — 업종 평균이 아니라 자기 과거와 "
                + "견주는 것이라 업종이 특이한 종목에도 쓸 수 있다. 적자에도 값이 남는 것이 "
                + "5년 PER 중앙값과 다른 점이다. 유리한 방향이 따로 없다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 21
)
public class PbrMedian5yCalculator implements IndicatorCalculator {

    public static final String CODE = "PBR_MEDIAN_5Y";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: financialYears만 선언하지 않는다 — 근거는 EpsCagr5yCalculator에 있다.
        return DataRequirement.priceAndFinancial(
                ValuationHistory.LOOKBACK + 1, EpsCagr5yCalculator.YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return ValuationHistory.median(context, (ctx, bar, quarters, published) -> {
            // 저량이다. 그 시점 잔액 하나를 쓴다 — 더하면 네 배가 된다.
            BigDecimal equity = quarters.get(published).totalEquity();
            if (equity == null || equity.signum() <= 0) {
                // 자본잠식 구간의 음수 PBR은 중앙값을 끌어내려 「싸다」로 읽힌다.
                return null;
            }
            return FinancialMath.perShareOn(ctx, bar, equity, quarters);
        });
    }
}
