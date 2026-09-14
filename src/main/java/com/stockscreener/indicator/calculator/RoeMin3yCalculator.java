package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.DataRequirement;
import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorCalculator;
import com.stockscreener.indicator.IndicatorCategory;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorMeta;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.IndicatorValueType;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 3년 최저 자기자본이익률 = {@code min(당기순이익 ÷ 기말 자본총계, 최근 3개 회계연도)}.
 *
 * <p>버핏 문서 3.1이다. 그 문서는 이 조건을 <b>"필터로 불가 → 그래프에서 육안 확인"</b>으로
 * 분류하고 §11에서 <i>"가장 중요한 건 3.1의 ROE 시계열 확인 — TTM 단일 시점만 보므로
 * 일회성 이익으로 ROE가 튄 해에 걸린 종목이 반드시 섞여 들어온다"</i>고 적는다. 그것은
 * TradingView의 한계이지 우리의 한계가 아니므로 지표로 만든다(ADR-0067).
 *
 * <p><b>평균이 아니라 최솟값이다.</b> 평균은 한 해의 일회성 이익이 나머지를 끌어올리는 것을
 * 막지 못한다 — 문서가 피하라고 한 함정이 정확히 그것이다. AAII 구현도 "최근 3개 회계연도
 * <b>각각</b> 15% 초과"를 요구하므로 최솟값이 그 조건과 같다.
 *
 * <h2>{@code ROE}와 기저가 다르다 — 같은 이름을 쓰지 않는 이유</h2>
 *
 * <p>{@link RoeCalculator}는 <b>최근 4개 분기</b> 순이익을 <b>최근 분기말</b> 자본으로 나눈다.
 * 이쪽은 <b>회계연도</b> 순이익을 <b>그 해 기말</b> 자본으로 나눈다. 두 값은 같은 회사에서도
 * 다르며, 그 차이는 분기 공시 시점 때문이지 어느 쪽이 틀려서가 아니다.
 *
 * <p>MUST: 세 해가 연속인지 {@code period_end} 간격으로 잰다. 행 수만 세면 한 해 빠진 종목이
 * 통과하고, 그 종목은 <b>이력이 짧아서가 아니라 구멍 때문에</b> 좋은 값을 받는다.
 *
 * <p>MUST NOT: 자본총계가 음수인 해를 그대로 나누지 않는다. 자본잠식이면 순손실이 <b>양수
 * ROE</b>로 나와 임계를 통과한다 — 부호가 두 번 뒤집히기 때문이고 값이 그럴듯해 드러나지 않는다.
 */
@Component
@IndicatorMeta(
        name = "3년 최저 ROE",
        description = "최근 3개 회계연도 각각의 자기자본이익률 가운데 가장 낮은 값. "
                + "세 해가 연속으로 있어야 계산하며, 한 해라도 없거나 자본이 음수면 값을 내지 않는다. "
                + "연간 순이익을 그 해 기말 자본으로 나눈 값이라 분기 기준인 ROE와 기저가 다르다.",
        interpretation = "높을수록 좋은 해뿐 아니라 나쁜 해에도 수익성을 유지했다는 뜻이다. "
                + "단일 시점 ROE는 일회성 이익으로 한 해만 튄 회사를 걸러내지 못하는데, "
                + "이 값은 세 해 중 가장 나쁜 해를 보므로 그 함정에 걸리지 않는다. "
                + "ROE보다 뚜렷하게 낮으면 최근 실적이 과거보다 좋아졌다는 뜻이기도 하다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 32,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 15),
                @IndicatorMeta.Band(label = "우수", from = 10, to = 15),
                @IndicatorMeta.Band(label = "양호", from = 5, to = 10),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 5),
                @IndicatorMeta.Band(label = "적자 해가 있다", to = 0)
        }
)
public class RoeMin3yCalculator implements IndicatorCalculator {

    public static final String CODE = "ROE_MIN_3Y";

    /** AAII 구현이 요구하는 회계연도 수. */
    static final int YEARS = 3;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: financialYears만 선언하지 않는다. priceBarsNeeded가 0이면
        // UnadjustedActionGuard가 창 0으로 불려 미보정 기업행위 억제(ADR-0055)가 무효가 된다.
        return DataRequirement.priceAndFinancial(1, YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, RoeMin3yCalculator::minimumRoe);
    }

    /** @return 세 해가 연속하고 모두 값이 있을 때만 최솟값. 아니면 null */
    private static BigDecimal minimumRoe(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (!FinancialMath.isConsecutiveAnnual(annual, YEARS)) {
            return null;
        }
        BigDecimal min = null;
        for (int i = 0; i < YEARS; i++) {
            FinancialFigures figures = annual.get(i);
            BigDecimal equity = FinancialMath.equityMatchingNetIncome(figures);
            if (figures.netIncome() == null || equity == null || equity.signum() <= 0) {
                // 자본잠식이면 순손실이 양수 ROE로 나온다. 위 javadoc 참조.
                return null;
            }
            BigDecimal roe = FinancialMath.percentage(figures.netIncome(), equity);
            if (min == null || roe.compareTo(min) < 0) {
                min = roe;
            }
        }
        return min;
    }
}
