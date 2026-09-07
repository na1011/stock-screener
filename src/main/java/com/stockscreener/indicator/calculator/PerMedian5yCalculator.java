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
 * 자기 자신의 5년 PER 중앙값.
 *
 * <p>린치가 "현재 PER이 자기 자신의 5년 평균 PER보다 낮을 것"을 요구한다(피터린치-방법론 3.5).
 * 원본 명세는 이것을 <b>스크리너 구현 불가</b>로 분류하고 그래프 육안 확인으로 넘겼다 —
 * 조건 우변에 지표를 놓을 수 없기 때문이다. 우리는 {@code PER < PER_MEDIAN_5Y}로 그대로 건다.
 *
 * <p>IMPORTANT — <b>{@code PER} 지표의 시계열을 그대로 쓰면 안 된다.</b> 그 계산기는 최근 TTM
 * 순이익 <b>하나</b>를 구해 모든 봉에 적용하므로(루프 밖에서 한 번 계산한다), 그 시계열의
 * 중앙값은 「5년간의 PER 중앙값」이 아니라 <b>「오늘의 이익으로 환산한 과거 주가 수준」</b>이다.
 * 이름은 밸류에이션 이력이라 말하면서 재는 것은 주가 이력이 되고, <b>값이 그럴듯해서 드러나지
 * 않는다.</b> 그래서 여기서는 봉마다 <b>그 시점에 공시돼 있던</b> 4개 분기로 TTM을 다시 세운다.
 *
 * <p>시점 판정은 {@code period_end}가 아니라 <b>{@code published_at}</b>으로 한다. 기말이
 * 지났어도 공시 전에는 시장이 그 이익을 몰랐고, {@code period_end}로 자르면 <b>미래를 알고
 * 계산한 PER</b>이 된다. 실측으로 {@code published_at}은 8,796행 전부 채워져 있다.
 *
 * <p>중앙값을 평균이 아니라 중앙값으로 두는 이유는 적자 전후의 극단값 때문이다. TTM 이익이
 * 0에 가까운 분기가 한 번만 끼어도 PER이 수천 배로 튀고, 평균은 그 한 점에 끌려간다.
 *
 * <p>중앙값은 <b>봉마다 그 봉까지의 창</b>으로 낸다({@link ValuationHistory#median}, ADR-0128).
 * 값 하나를 창 전체에 펴던 시절에는 과거 날짜의 행이 <b>그 뒤의 종가로 계산한 중앙값</b>이었다.
 */
@Component
@IndicatorMeta(
        name = "5년 PER 중앙값",
        description = "지난 5년 동안 이 종목의 PER이 놓였던 자리의 한가운데 값. "
                + "매 거래일의 주가를 그날 공시돼 있던 4개 분기 이익으로 나눠 모은 뒤 중앙값을 취한다.",
        interpretation = "지금 PER을 옆에 두고 읽는 참조값이다. 현재 PER이 이 값보다 낮으면 "
                + "이 회사 기준으로 싼 구간에 있다는 뜻이다 — 업종 평균이 아니라 "
                + "자기 과거와 견주는 것이라 업종이 특이한 종목에도 쓸 수 있다. "
                + "유리한 방향이 따로 없다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 12
)
public class PerMedian5yCalculator implements IndicatorCalculator {

    public static final String CODE = "PER_MEDIAN_5Y";

    /** 창과 최소 표본은 형제 지표와 공유한다. */
    static final int LOOKBACK = ValuationHistory.LOOKBACK;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: financialYears만 선언하지 않는다 — 근거는 EpsCagr5yCalculator에 있다.
        return DataRequirement.priceAndFinancial(
                LOOKBACK + 1, EpsCagr5yCalculator.YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return ValuationHistory.median(context, (ctx, bar, quarters, published) -> {
            // PER과 같은 분자여야 한다 — 갈리면 PER_DISCOUNT_5Y가 두 기준의 비율이 된다.
            BigDecimal ttm = FinancialMath.sumControllingNetIncome(quarters, published);
            if (ttm == null || ttm.signum() <= 0) {
                // 적자 구간의 음수 PER은 중앙값을 끌어내려 「싸다」로 읽힌다.
                return null;
            }
            return FinancialMath.perShareOn(ctx, bar, ttm, quarters);
        });
    }
}
