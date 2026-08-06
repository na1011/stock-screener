package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;
import com.stockscreener.indicator.PriceBar;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 주가수익비율(PER) = 주가 ÷ 주당순이익(TTM).
 *
 * <p>외부 API가 주는 PER을 쓰지 않고 원자료에서 계산한다(spec §7). 소스마다 순이익 기준
 * (TTM/연간/추정)과 주식수 기준(보통주/희석)이 달라 두 시장의 값이 어긋나기 때문이다.
 *
 * <p>계산 규칙 두 가지가 중요하다.
 * <ul>
 *   <li><b>{@link PriceBar#close()}(원주가)를 쓴다.</b> 수정주가가 아니다 — 시점의 발행주식수와
 *       짝이 맞아야 하고, 사용자가 실제 지불하는 가격이어야 한다.
 *   <li><b>순이익은 최근 4개 분기 합(TTM)이다.</b> 연간 값만 쓰면 결산 직후까지 1년 가까이
 *       낡은 값이 된다. 분기가 4개 미만이면 계산하지 않는다.
 * </ul>
 *
 * <p>순이익이 음수(적자)면 PER은 의미가 없으므로 값을 내지 않는다. 음수 PER을 저장하면
 * "PER이 낮은 종목"을 찾는 조건에 적자 기업이 최상위로 올라온다.
 */
@Component
@IndicatorMeta(
        name = "PER",
        description = "주가를 주당순이익으로 나눈 값. 이익 대비 주가가 비싼지 판단하는 대표적인 밸류에이션 지표다.",
        interpretation = "낮을수록 이익 대비 주가가 싸다는 뜻이다. 다만 업종마다 평균이 다르므로 같은 업종 안에서 비교하는 것이 좋다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.LOWER_IS_BETTER,
        decimalScale = 2,
        defaultColumn = true,
        sortOrder = 10
)
public class PerCalculator implements IndicatorCalculator {

    public static final String CODE = "PER";
    private static final int TTM_QUARTERS = 4;
    private static final int SCALE = 8;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 시세는 계산 시점의 종가만 필요하지만, 성장률 계산 등과 창을 맞추기 위해 1봉을 선언한다.
        //
        // 재무 1년은 분기로 정확히 4개이고 TTM이 요구하는 수와 같다 — 여유가 0이다.
        // 그 여유는 IndicatorDataReader가 분기를 읽을 때 한 해치를 더 얹어 확보한다.
        // 여기서 2년을 선언하지 않는 이유는 이 숫자가 "이 지표가 의미상 필요로 하는 기간"이고,
        // 소스 누락에 대비한 여유는 읽는 쪽의 책임이기 때문이다.
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        BigDecimal eps = trailingTwelveMonthEps(context);
        if (eps == null || eps.signum() <= 0) {
            // 적자이거나 재무가 부족하면 계산하지 않는다.
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>(context.priceBars().size());
        for (PriceBar bar : context.priceBars()) {
            BigDecimal per = bar.close().divide(eps, SCALE, RoundingMode.HALF_UP);
            points.add(new IndicatorPoint(bar.tradeDate(), per));
        }
        return points;
    }

    /**
     * 최근 4개 분기 순이익 합을 발행주식수로 나눈 주당순이익.
     *
     * <p>분기가 4개 미만이거나 필요한 항목이 비어 있으면 null을 반환한다.
     */
    private BigDecimal trailingTwelveMonthEps(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        if (quarters.size() < TTM_QUARTERS) {
            return null;
        }

        BigDecimal netIncomeSum = BigDecimal.ZERO;
        for (int i = 0; i < TTM_QUARTERS; i++) {
            BigDecimal netIncome = quarters.get(i).netIncome();
            if (netIncome == null) {
                return null;
            }
            netIncomeSum = netIncomeSum.add(netIncome);
        }

        // 발행주식수는 가장 최근 분기 값을 쓴다. 기간 중 변동이 있어도 최신 상태가
        // 현재 주가와 짝이 맞기 때문이다.
        BigDecimal shares = quarters.get(0).sharesOutstanding();
        if (shares == null || shares.signum() <= 0) {
            return null;
        }
        return netIncomeSum.divide(shares, SCALE, RoundingMode.HALF_UP);
    }
}
