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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 유보 1원당 시가 창출(4년) = {@code 주가 증가분 ÷ 주당 이익잉여금 증가분}.
 *
 * <p>버핏 문서 3.5, <b>"1달러 유보 → 1달러 이상의 시가"</b> 원칙이다. 회사가 배당하지 않고
 * 쌓아 둔 이익 1원이 시장가치를 1원 넘게 만들어 냈는가를 묻는다 — <i>"유보이익을 비생산적으로
 * 쓴 회사는 주가 부진으로 드러난다."</i> 문서는 이 조건을 <b>"필드 간 비교가 필요해 스크리너
 * 구현 불가"</b>로 분류하지만 그것은 TradingView의 한계다(ADR-0067).
 *
 * <h2>4년인 이유는 이력이 아니라 거래일 좌표다</h2>
 *
 * <p>원문과 AAII는 5년을 쓴다. 우리가 4년인 것은 <b>분자와 분모의 기준점을 맞추기 위해서다.</b>
 * 워밍업 1,261봉 전이 2021-06인데 FY−5의 기말은 <b>1,377봉</b> 전이다 — 5년으로 두면 주가는
 * 2021-06부터, 이익잉여금은 FY−5 기말부터 재어 <b>기준점이 7개월 어긋난 채 남는다.</b>
 * FY−4 기말은 <b>1,129봉</b>이라 창 안이고 결산월이 12월이 아닌 종목까지 여유가 있다.
 * <b>어긋난 5년보다 정직한 4년이 낫다</b>는 판단이며, 창을 1,377로 올리는 대안은 얻는 것이
 * 한 해뿐이라 버렸다(ADR-0067 §버린 대안).
 *
 * <h2>축척을 맞추는 법 — 과거 주식수를 읽지 않는다</h2>
 *
 * <p>{@code adj_close}는 <b>현재 주식수 축척</b>이다(실측: {@code adj_factor}가 과거에 1보다
 * 작고 최신 봉에서 1이다). 그래서 분모의 주당 값도 <b>같은 축척</b>이어야 하고, 양 끝
 * 이익잉여금을 <b>같은 주식수</b>로 나누면 그것이 성립한다 — 분할·무상증자가 양쪽에서 정확히
 * 약분되므로 {@link EpsCagr5yCalculator}처럼 과거 주식수를 따로 읽어 배수를 걷어낼 필요가 없다.
 * <b>과거 주식수를 읽어 놓고 배수를 안 걷는 것이 이 자리에서 가장 흔한 오류이며</b>, 그러면
 * 5:1 분할이 「주당 유보가 5분의 1로 줄었다」로 읽힌다.
 *
 * <h2>MUST NOT: 유상증자가 이 값을 좋아 보이게 만든다는 것을 감추지 않는다</h2>
 *
 * <p>신주 발행은 주식수를 늘리지만 <b>이익잉여금은 늘리지 않는다</b>(납입금은 자본잉여금으로
 * 간다). 그래서 분모가 작아지고 값이 커진다. 권리락 보정이 과거 주가를 낮추므로 분자도
 * 커져 <b>같은 방향으로 두 번 작용한다.</b> 이 지표만으로 자본배분을 판정하지 않으며,
 * 프리셋에서 {@code SHARES_GROWTH_3Y}가 그 축을 따로 본다.
 *
 * <p>MUST NOT: 이익잉여금이 줄어든 회사에 값을 내지 않는다. 분모가 0 이하면 "유보 1원당"이라는
 * 물음 자체가 성립하지 않고, 음수 분모로 나누면 <b>주가가 오른 회사가 음수를 받아 최하위로
 * 밀린다.</b>
 */
@Component
@IndicatorMeta(
        name = "유보 1원당 시가 창출(4년)",
        description = "4개 회계연도 동안 주가가 오른 폭을 같은 기간 주당 이익잉여금이 늘어난 폭으로 "
                + "나눈 값. 다섯 해가 연속으로 있고 그 기간의 시세가 남아 있어야 계산하며, "
                + "이익잉여금이 줄어든 회사는 값을 내지 않는다. "
                + "원문은 5년이지만 시세와 회계연도의 기준점을 맞추려고 4년으로 뒀다.",
        interpretation = "1보다 크면 회사가 배당하지 않고 쌓아 둔 이익 1원이 그보다 큰 시장가치를 "
                + "만들어 냈다는 뜻이다. 1보다 작으면 유보한 돈이 그만큼의 가치를 만들지 못했다는 "
                + "신호이며, 자본배분이 나빴거나 시장이 아직 인정하지 않는 것이다. "
                + "유상증자를 한 회사는 이 값이 실제보다 좋게 나온다 — 주식수만 늘고 이익잉여금은 "
                + "늘지 않기 때문이다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 63,
        bands = {
                @IndicatorMeta.Band(label = "매우 높음", from = 3),
                @IndicatorMeta.Band(label = "높음", from = 1, to = 3),
                @IndicatorMeta.Band(label = "낮음", from = 0, to = 1),
                @IndicatorMeta.Band(label = "주가가 오히려 내렸다", to = 0)
        }
)
public class RetainedToMarket4yCalculator implements IndicatorCalculator {

    public static final String CODE = "RETAINED_TO_MARKET_4Y";

    /** 재는 구간. 위 javadoc의 「4년인 이유」가 이 값을 정한다. */
    static final int YEARS = 4;

    /**
     * 읽어야 하는 봉 수.
     *
     * <p>FY−4 기말이 실측으로 1,129봉 전이고, 결산월이 12월이 아닌 종목은 그보다 멀 수 있어
     * 여유를 둔다. <b>1,261은 이미 저장소의 워밍업 최댓값이므로</b>({@link EpsCagr5yCalculator})
     * 이 지표는 창을 밀지 않는다 — 창이 오르면 평활 시드가 밀려 스냅숏 대조가 따라붙는다.
     *
     * <p><b>앵커는 날짜로 찾는다</b>({@code anchorIndex}). 그래서 읽기 창이 넓어져도 값은 물론
     * <b>커버리지도 안 는다</b> — FY−4 기말이 현행 창 앞머리보다 이른 종목이 0이기 때문이다.
     * 값이 없는 종목의 사유는 연간 부족·유보 미증가·미보정 억제이지 창이 아니다.
     */
    static final int LOOKBACK = 1261;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 연간 5행 = 4년 구간. MUST NOT: financialYears만 선언하지 않는다(ADR-0055·0066).
        return DataRequirement.priceAndFinancial(LOOKBACK, YEARS + 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.bySegment(context, RetainedToMarket4yCalculator::pointsFrom);
    }

    /**
     * 그 봉 시점에 공시돼 있던 재무만 담긴 컨텍스트로 낸 시계열(ADR-0128).
     *
     * <p>재무 합산이 이 루프 <b>밖</b>에 있는 것이 옳다 — 이 메서드 자체가 한
     * 공시 구간에 대해 불리고, 구간 밖 봉의 값은 {@code bySegment}가 버린다.
     */
    private static List<IndicatorPoint> pointsFrom(IndicatorContext context) {
        List<FinancialFigures> annual = context.annualFigures();
        if (!FinancialMath.isConsecutiveAnnual(annual, YEARS + 1)) {
            return List.of();
        }
        BigDecimal recent = annual.get(0).retainedEarnings();
        BigDecimal past = annual.get(YEARS).retainedEarnings();
        if (recent == null || past == null) {
            return List.of();
        }
        BigDecimal retainedGain = recent.subtract(past);
        if (retainedGain.signum() <= 0) {
            // 유보가 늘지 않았으면 "유보 1원당"이 성립하지 않는다. 위 javadoc 참조.
            return List.of();
        }

        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return List.of();
        }
        int anchorIndex = anchorIndex(bars, annual.get(YEARS).periodEnd());
        if (anchorIndex < 0) {
            // 창이 그 회계연도 기말까지 닿지 않는다. 억지로 가장 오래된 봉을 쓰면
            // 이름이 말하는 4년이 아닌 구간을 재게 된다.
            return List.of();
        }

        // 양 끝을 같은 주식수로 나눈다 — 축척이 adj_close와 맞고 분할이 약분된다.
        PriceBar lastBar = bars.get(bars.size() - 1);
        BigDecimal shares = FinancialMath.sharesOf(context, lastBar, context.quarterlyFigures());
        if (shares == null || shares.signum() <= 0) {
            return List.of();
        }
        BigDecimal retainedGainPerShare =
                retainedGain.divide(shares, FinancialMath.SCALE, RoundingMode.HALF_UP);
        if (retainedGainPerShare.signum() <= 0) {
            return List.of();
        }

        BigDecimal anchorPrice = bars.get(anchorIndex).adjClose();
        if (anchorPrice == null) {
            return List.of();
        }

        List<IndicatorPoint> points = new ArrayList<>(bars.size() - anchorIndex);
        for (int i = anchorIndex + 1; i < bars.size(); i++) {
            PriceBar bar = bars.get(i);
            if (bar.adjClose() == null) {
                continue;
            }
            BigDecimal priceGain = bar.adjClose().subtract(anchorPrice);
            points.add(new IndicatorPoint(bar.tradeDate(),
                    priceGain.divide(retainedGainPerShare, MovingAverages.SCALE,
                            RoundingMode.HALF_UP)));
        }
        return points;
    }

    /**
     * 기준 회계연도 기말 <b>이하</b>의 마지막 봉. 기말이 휴장일이면 직전 거래일이 된다.
     *
     * @return 창이 그 날짜까지 닿지 않으면 −1
     */
    private static int anchorIndex(List<PriceBar> bars, LocalDate periodEnd) {
        if (periodEnd == null || bars.get(0).tradeDate().isAfter(periodEnd)) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).tradeDate().isAfter(periodEnd)) {
                break;
            }
            found = i;
        }
        return found;
    }
}
