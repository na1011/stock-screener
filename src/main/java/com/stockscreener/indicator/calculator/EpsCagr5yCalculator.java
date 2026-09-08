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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 5년 주당순이익 연평균 성장률(CAGR) = {@code (최근 4분기 EPS ÷ 5년 전 4분기 EPS)^(1/5) − 1}.
 *
 * <p>린치가 <b>상한</b>으로 쓰는 값이다 — "연 50~100% 성장은 의심하라. 그 속도는 유지되지 않으며
 * 경쟁자를 불러들인다"(피터린치-방법론 3.17). 그래서 조건이 {@code BETWEEN 0 AND 50}이고,
 * 이 지표는 <b>높을수록 좋은 값이 아니다.</b>
 *
 * <p>{@link EpsGrowthTtmYoyCalculator}의 세 규칙을 그대로 물려받는다. 되풀이해 적는 이유는
 * 기간만 다른 같은 함정이고, 어느 하나라도 빠지면 <b>값은 그럴듯하고 이름은 EPS라고 말하기</b>
 * 때문이다.
 * <ul>
 *   <li><b>주식수를 양 끝에서 각각 읽는다.</b> 같은 날짜 주식수를 쓰면 약분되어 값이 순이익
 *       CAGR과 정확히 같아진다. {@code financial_statement.eps}·{@code shares_outstanding}은
 *       전량 비어 있어 쓸 수 없고, 주식수의 정본은 {@code price_daily.listed_shares}다(ADR-0013).
 *       <b>이 한 가지가 워밍업 최댓값을 1,261봉으로 미는 유일한 이유다</b>(ADR-0066).
 *       읽기 창은 그 위에 보존 일수를 얹은 1,516이다(ADR-0079) — 둘을 같은 낱말로 쓰지 않는다.
 *   <li><b>분할·무상증자가 만든 배수는 걷어낸다.</b> 희석이 아니기 때문이다(ADR-0051).
 *   <li><b>기저가 0 이하면 값을 내지 않는다.</b> 적자가 줄기만 해도 양수 성장률이 나온다.
 * </ul>
 *
 * <p>IMPORTANT — <b>분기 인덱스가 아니라 실제 기간을 확인한다.</b> 읽기는
 * {@code period_end} 내림차순이라 인덱스 20이 「20분기 전」이지 「5년 전」이 아니다. 공시가
 * 빠진 종목에서는 그 둘이 갈리는데, 그때 이름은 5년이라 말하면서 4년이나 6년을 잰다.
 * {@link #YEARS}년에서 한 분기 이상 어긋나면 값을 내지 않는다.
 */
@Component
@IndicatorMeta(
        name = "5년 주당순이익 성장률(CAGR)",
        description = "최근 4개 분기 주당순이익이 5년 전 같은 넉 달치와 견줘 해마다 평균 몇 퍼센트씩 "
                + "늘었는지. 주식수를 양 끝에서 각각 읽어 희석까지 반영한다.",
        interpretation = "높을수록 오래 성장했다는 뜻이지만 무조건 높은 쪽이 좋지는 않다 — "
                + "연 50%를 넘는 성장은 유지되기 어렵고 경쟁자를 불러들인다는 것이 "
                + "이 지표를 상한으로 쓰는 이유다. "
                + "5년 전이 적자였거나 이력이 짧은 종목은 값이 없다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.NEUTRAL,
        decimalScale = 2,
        sortOrder = 72,
        bands = {
                @IndicatorMeta.Band(label = "지속 어려움", from = 50),
                @IndicatorMeta.Band(label = "고성장", from = 20, to = 50),
                @IndicatorMeta.Band(label = "성장", from = 5, to = 20),
                @IndicatorMeta.Band(label = "정체", from = 0, to = 5),
                @IndicatorMeta.Band(label = "역성장", to = 0)
        }
)
public class EpsCagr5yCalculator implements IndicatorCalculator {

    public static final String CODE = "EPS_CAGR_5Y";

    /** 견주는 기간. 이름과 계산과 검사가 전부 이 상수를 본다. */
    public static final int YEARS = 5;

    /** 5년 전 주식수를 읽을 거리. 1년을 252거래일로 보는 저장소 관례를 따른다. */
    static final int LOOKBACK = 252 * YEARS;

    /** {@code sumFourQuarters}의 오프셋. 4분기 × 5년이다. */
    private static final int QUARTER_OFFSET = FinancialMath.TTM_QUARTERS * YEARS;

    /** 실제 기간이 5년에서 벗어나도 되는 한계. 한 분기다. */
    private static final long PERIOD_TOLERANCE_DAYS = 92;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // MUST NOT: financialYears만 선언하지 않는다. priceBarsNeeded가 0이면
        // effectiveLookback이 0이 되고, UnadjustedActionGuard가 창 0으로 불려
        // 미보정 기업행위 억제(ADR-0055)가 통째로 무효가 된다.
        //
        // 분기 스물넷이 필요하므로 재무는 6년이다(연간 6행 = 5년 구간).
        return DataRequirement.priceAndFinancial(LOOKBACK + 1, YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.bySegment(context, EpsCagr5yCalculator::pointsFrom);
    }

    /**
     * 그 봉 시점에 공시돼 있던 재무만 담긴 컨텍스트로 낸 시계열(ADR-0128).
     *
     * <p>재무 합산이 이 루프 <b>밖</b>에 있는 것이 옳다 — 이 메서드 자체가 한
     * 공시 구간에 대해 불리고, 구간 밖 봉의 값은 {@code bySegment}가 버린다.
     */
    private static List<IndicatorPoint> pointsFrom(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        BigDecimal recentTtm =
                FinancialMath.sumControllingNetIncome(quarters, 0);
        BigDecimal priorTtm = FinancialMath.sumControllingNetIncome(quarters, QUARTER_OFFSET);
        if (recentTtm == null || priorTtm == null || priorTtm.signum() <= 0) {
            return List.of();
        }
        if (!spansFiveYears(quarters)) {
            return List.of();
        }

        List<PriceBar> bars = context.priceBars();
        if (bars.size() <= LOOKBACK) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - LOOKBACK);
        for (int i = LOOKBACK; i < bars.size(); i++) {
            PriceBar pastBar = bars.get(i - LOOKBACK);
            PriceBar nowBar = bars.get(i);

            BigDecimal pastShares =
                    FinancialMath.sharesOf(context, pastBar, context.quarterlyFigures());
            BigDecimal nowShares = FinancialMath.sharesOf(context, nowBar, context.quarterlyFigures());
            if (pastShares == null || nowShares == null
                    || pastShares.signum() <= 0 || nowShares.signum() <= 0) {
                continue;
            }
            BigDecimal scaledPastShares = pastShares.multiply(
                    context.corporateActionMultiple(pastBar.tradeDate(), nowBar.tradeDate()));
            if (scaledPastShares.signum() <= 0) {
                continue;
            }

            BigDecimal recentEps =
                    recentTtm.divide(nowShares, FinancialMath.SCALE, RoundingMode.HALF_UP);
            BigDecimal priorEps =
                    priorTtm.divide(scaledPastShares, FinancialMath.SCALE, RoundingMode.HALF_UP);
            if (priorEps.signum() <= 0 || recentEps.signum() <= 0) {
                continue;
            }

            double ratio = recentEps.divide(priorEps, MovingAverages.MC).doubleValue();
            double cagr = (Math.pow(ratio, 1.0 / YEARS) - 1.0) * 100.0;
            points.add(new IndicatorPoint(nowBar.tradeDate(),
                    BigDecimal.valueOf(cagr)
                            .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }

    /**
     * 두 TTM 구간의 끝이 실제로 5년 떨어져 있는가. 위 IMPORTANT가 이 검사를 설명한다.
     *
     * <p>인덱스만 믿으면 공시가 빠진 종목에서 <b>이름이 말하는 기간과 값이 재는 기간이 갈린다.</b>
     */
    private static boolean spansFiveYears(List<FinancialFigures> quarters) {
        if (quarters.size() <= QUARTER_OFFSET) {
            return false;
        }
        var recentEnd = quarters.get(0).periodEnd();
        var priorEnd = quarters.get(QUARTER_OFFSET).periodEnd();
        if (recentEnd == null || priorEnd == null) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(priorEnd, recentEnd);
        long expected = Math.round(YEARS * 365.25);
        return Math.abs(days - expected) <= PERIOD_TOLERANCE_DAYS;
    }
}
