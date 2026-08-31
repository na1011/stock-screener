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
 * TTM 주당순이익의 전년 대비 성장률 = (최근 4분기 EPS ÷ 그 직전 4분기 EPS − 1) × 100.
 *
 * <p>세 가지를 지키지 않으면 <b>이름이 재는 것과 값이 재는 것이 달라진다.</b>
 *
 * <p><b>① 주식수를 양 끝에서 각각 읽는다.</b> 같은 날짜의 주식수를 분자·분모에 쓰면
 * <b>약분되어 값이 TTM 순이익 성장률과 정확히 같아진다.</b> 그러면 순이익이 아니라 EPS를
 * 보는 이유(희석)를 하나도 못 재는데, 값은 그럴듯하고 이름은 EPS라고 말한다.
 * {@code financial_statement.eps}는 전량 비어 있어 쓸 수 없고, 주식수의 정본은
 * {@code price_daily.listed_shares}다.
 *
 * <p><b>② 분할·무상증자가 만든 배수는 걷어낸다.</b> 액면분할로 주식수가 두 배가 되면 EPS는
 * 절반이 되지만 그것은 희석이 아니다. 걷어내지 않으면 분할한 해에 −50% 성장으로 나온다.
 * {@code SHARES_GROWTH_3Y}가 같은 이유로 같은 보정을 한다.
 *
 * <p><b>③ 기저가 0 이하면 값을 내지 않는다.</b> 적자에서 적자가 줄기만 해도 양수 「성장률」이
 * 나와 「+25% 이상」 같은 조건을 통과한다. 실측에서 <b>직전 TTM 순이익이 0 이하인 종목이
 * 69개</b>였다 — 거르지 않으면 그 69종목이 성장주 조건의 상위에 앉는다.
 *
 * <p>MUST NOT: 이 값을 CANSLIM의 「C(당분기 대 전년동기)」나 「A(3년 연간 성장)」라고 부르지
 * 않는다. 둘 다 아니다 — 재는 것은 <b>TTM 대 직전 TTM</b> 하나다.
 */
@Component
@IndicatorMeta(
        name = "TTM 주당순이익 성장률",
        description = "최근 4개 분기 주당순이익이 그 직전 4개 분기보다 몇 퍼센트 늘었는지. "
                + "주식수를 양 끝에서 각각 읽어 희석까지 반영하며, 액면분할·무상증자가 만든 "
                + "주식수 변화는 걷어낸다.",
        interpretation = "높을수록 주주 몫의 이익이 빠르게 늘었다는 뜻이다. "
                + "직전 4개 분기가 적자였던 종목은 값을 내지 않는다 — 적자가 줄어든 것을 "
                + "성장으로 읽지 않기 위해서다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.PERCENT,
        unit = "%",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 71
)
public class EpsGrowthTtmYoyCalculator implements IndicatorCalculator {

    public static final String CODE = "EPS_GROWTH_TTM_YOY";

    /** 1년 전 주식수를 읽을 거리. 재무의 4분기와 짝을 맞춘다. */
    private static final int LOOKBACK = 252;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 분기 여덟 개가 필요하므로 재무는 2년이다.
        return DataRequirement.priceAndFinancial(LOOKBACK + 1, 2);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        List<FinancialFigures> quarters = context.quarterlyFigures();
        BigDecimal recentTtm =
                FinancialMath.sumControllingNetIncome(quarters, 0);
        BigDecimal priorTtm =
                FinancialMath.sumControllingNetIncome(quarters, 4);
        // ③ 기저가 0 이하면 증감률이 뜻을 잃는다.
        if (recentTtm == null || priorTtm == null || priorTtm.signum() <= 0) {
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
            // ② 분할·무상증자가 만든 배수는 희석이 아니므로 과거 쪽을 같은 축척으로 옮긴다.
            BigDecimal scaledPastShares = pastShares.multiply(
                    context.corporateActionMultiple(pastBar.tradeDate(), nowBar.tradeDate()));
            if (scaledPastShares.signum() <= 0) {
                continue;
            }

            // ① 양 끝의 주식수가 다르므로 약분되지 않는다.
            //
            // 미국에서는 한동안 거짓이었다. sharesOf가 quarters.get(0) 한 행을 양 끝에 써서
            // 주식수가 완전히 약분됐고, 이 지표가 「주당」이 아니라 순수 순이익 성장률을
            // 냈다 — 값이 그럴듯해 상수 판정에도 안 걸렸다. ADR-0103이 봉 시점 분기를
            // 고르게 해 그 상쇄를 깼다.
            BigDecimal recentEps = recentTtm.divide(nowShares, FinancialMath.SCALE,
                    RoundingMode.HALF_UP);
            BigDecimal priorEps = priorTtm.divide(scaledPastShares, FinancialMath.SCALE,
                    RoundingMode.HALF_UP);
            if (priorEps.signum() <= 0) {
                continue;
            }

            points.add(new IndicatorPoint(nowBar.tradeDate(),
                    recentEps.divide(priorEps, MovingAverages.MC)
                            .subtract(BigDecimal.ONE)
                            .multiply(HUNDRED)
                            .setScale(MovingAverages.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
