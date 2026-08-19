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
 * 3년 최저 세전 투하자본이익률 =
 * {@code min(영업이익 ÷ (자산총계 − 유동부채), 최근 3개 회계연도)}.
 *
 * <p>{@link RoicPretaxCalculator}가 <b>단일 시점 TTM</b>이라 분기 공시마다 값이 뒤집힌다.
 * 버핏 프리셋의 다른 질 게이트({@code ROE_MIN_3Y}·{@code PROFIT_STREAK_Y})가 전부 다년
 * 기준인데 ROIC만 그렇지 않아, 목록에 들고 나는 것이 사업의 변화가 아니라 분기 잡음에서
 * 온다. 실측으로 같은 임계에서 종목·연 단위 임계 교차율이 <b>12~20%(분기 TTM)</b> 대
 * <b>4.5~9.8%(3년 최저)</b>였다.
 *
 * <h2>기저가 {@code ROIC_PRETAX}와 다르다 — 같은 이름을 쓰지 않는 이유</h2>
 *
 * <p>{@code ROIC_PRETAX}는 <b>최근 4개 분기</b> 영업이익을 <b>최근 분기말</b> 투하자본으로
 * 나눈다. 이쪽은 <b>회계연도</b> 영업이익을 <b>그 해 기말</b> 투하자본으로 나눈다. 두 값은
 * 같은 회사에서도 다르고, 3년 최저는 단년 TTM보다 <b>구조적으로 낮다</b> — 같은 수의 임계가
 * 같은 엄격도를 뜻하지 않는다.
 *
 * <p>MUST NOT: 분자를 {@link FinancialMath#sumTrailingFourQuarters}로 만들지 않는다. 연간
 * 행의 영업이익은 <b>이미 그 회계연도 전체</b>이고 자산·유동부채는 <b>그 해 기말 잔액</b>이라,
 * 한 행에서 분자와 분모를 함께 뽑는 것이 옳다. 네 해를 합하면 4년 누적 이익을 1년 자본으로
 * 나누게 되는데 <b>값이 그럴듯해 드러나지 않는다.</b>
 *
 * <p>MUST: 세 해가 연속인지 {@code period_end} 간격으로 잰다. 행 수만 세면 한 해 빠진 종목이
 * 통과하고, 그 종목은 <b>이력이 짧아서가 아니라 구멍 때문에</b> 좋은 값을 받는다.
 *
 * <p>MUST NOT: 투하자본이 0 이하인 해를 그대로 나누지 않는다. 자산총계가 유동부채보다 작으면
 * 영업적자가 <b>양수 ROIC</b>로 나온다 — 부호가 두 번 뒤집히기 때문이고,
 * {@link FinancialMath#percentage}는 음수 분모를 거르지 않는다.
 *
 * <p>금융업에서 값이 없는 것은 {@code ROIC_PRETAX}와 같은 이유다 — K-IFRS 금융업은 재무상태표를
 * 유동/비유동으로 가르지 않아 분모의 재료가 오지 않는다. {@code IndicatorUnavailabilityConfig}가
 * 그 사유를 화면에 말한다.
 */
@Component
@IndicatorMeta(
        name = "3년 최저 ROIC(세전)",
        description = "최근 3개 회계연도 각각의 세전 투하자본이익률 가운데 가장 낮은 값. "
                + "세 해가 연속으로 있어야 계산하며, 한 해라도 없거나 투하자본이 0 이하면 값을 내지 않는다. "
                + "연간 영업이익을 그 해 기말 투하자본으로 나눈 값이라 분기 기준인 투하자본이익률(세전)과 기저가 다르다. "
                + "유동부채를 구분하지 않는 금융업에서는 값이 나오지 않는다.",
        interpretation = "높을수록 좋은 해뿐 아니라 나쁜 해에도 사업에 묶인 자본의 수익성을 유지했다는 뜻이다. "
                + "단일 시점 ROIC는 일회성 이익으로 한 해만 튄 회사를 걸러내지 못하는데, "
                + "이 값은 세 해 중 가장 나쁜 해를 보므로 그 함정에 걸리지 않는다. "
                + "투하자본이익률(세전)보다 뚜렷하게 낮으면 최근 실적이 과거보다 좋아졌다는 뜻이기도 하다. "
                + "세전 기준이라 세후 기준으로 쓰인 외부 문헌의 임계값과 직접 견주면 안 된다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 56,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 15),
                @IndicatorMeta.Band(label = "우수", from = 10, to = 15),
                @IndicatorMeta.Band(label = "양호", from = 5, to = 10),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 5),
                @IndicatorMeta.Band(label = "영업적자 해가 있다", to = 0)
        }
)
public class RoicMin3yCalculator implements IndicatorCalculator {

    public static final String CODE = "ROIC_MIN_3Y";

    /** {@code ROE_MIN_3Y}와 같은 회계연도 수. 두 게이트가 같은 창을 봐야 대비가 뜻을 갖는다. */
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
        return FinancialMath.spreadOverTradeDates(context.priceBars(), minimumRoic(context));
    }

    /** @return 세 해가 연속하고 모두 값이 있을 때만 최솟값. 아니면 null */
    private static BigDecimal minimumRoic(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (!FinancialMath.isConsecutiveAnnual(annual, YEARS)) {
            return null;
        }
        BigDecimal min = null;
        for (int i = 0; i < YEARS; i++) {
            FinancialFigures figures = annual.get(i);
            BigDecimal operatingIncome = figures.operatingIncome();
            BigDecimal assets = figures.totalAssets();
            BigDecimal currentLiabilities = figures.currentLiabilities();
            if (operatingIncome == null || assets == null || currentLiabilities == null) {
                return null;
            }
            BigDecimal investedCapital = assets.subtract(currentLiabilities);
            if (investedCapital.signum() <= 0) {
                // 분모가 음수면 영업적자가 양수 ROIC로 나온다. 위 javadoc 참조.
                return null;
            }
            BigDecimal roic = FinancialMath.percentage(operatingIncome, investedCapital);
            if (min == null || roic.compareTo(min) < 0) {
                min = roic;
            }
        }
        return min;
    }
}
