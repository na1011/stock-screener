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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;

/**
 * 순유동자산가치(NCAV) ÷ 시가총액.
 *
 * <p>NCAV = 유동자산 − <b>총부채</b>. 그레이엄이 <i>Security Analysis</i>에서 세운 값이고,
 * Oppenheimer(1986, <i>FAJ</i>)와 그 뒤의 재현들이 <b>자산가격모형으로 설명되지 않는 초과수익</b>을
 * 보고했다. 원문의 매수 기준은 <b>주가가 NCAV의 3분의 2 이하</b>이므로 이 값으로는 <b>1.5 이상</b>이다.
 *
 * <p>IMPORTANT — <b>유동부채가 아니라 총부채를 뺀다.</b> 비유동부채까지 빼는 것이 이 값의 요점이고,
 * 그래서 「유동비율」과 뜻이 전혀 다르다. 유동부채만 빼면 순운전자본이 되는데 <b>그것은 훨씬 큰
 * 수라 「그레이엄 기준 충족」이 무더기로 나온다.</b>
 *
 * <p><b>국내 대형주에서 대부분 음수다.</b> 그것은 결함이 아니라 이 지표가 재는 것이다 — 코스피200·
 * 코스닥150은 설비와 차입을 갖춘 회사들이라 유동자산이 총부채를 넘는 경우가 드물다. <b>0건이
 * 나와도 임계를 푸는 근거로 읽지 않는다</b>(ADR-0044 §승인된 예외가 프리셋과 무관하게 살려 둔 규칙).
 * 연속값으로 두는 이유가 이것이다 — 충족 여부가 아니라 <b>자산 뒷받침의 정도</b>를 정렬한다.
 *
 * <p>시가총액을 <b>다시 계산하지 않고 의존으로 받는다</b>({@code FcfYieldCalculator}와 같다).
 * 여기서 {@link FinancialMath#sharesOf}로 접지 않는 이유는 이 값이 <b>주당 배수가 아니라
 * 시가총액 대비 비율</b>이기 때문이다 — {@code PSR}이 반대 판단을 한 자리이고, 두 판단의 갈림은
 * 「형제 배수 지표와 기저를 맞추는가」 대 「시가총액 계열과 기저를 맞추는가」다.
 *
 * <p>재무는 <b>최근 분기 잔액</b>을 쓴다. 유동자산·총부채는 저량이므로 4분기를 더하면 4배가 되고
 * <b>값이 그럴듯해 드러나지 않는다.</b>
 *
 * <p>유동자산은 활성 편입 350종목 중 <b>322</b>에만 있다(2026-08-21 실측). 없는 종목은 대부분
 * 금융업이며 <b>유동/비유동을 구분하지 않는 회계</b>라 결손이 아니라 업종이다.
 */
@Component
@IndicatorMeta(
        name = "순유동자산 대비 시가총액",
        description = "유동자산에서 총부채를 뺀 값이 시가총액의 몇 배인지. "
                + "회사를 지금 청산해 유동자산으로 빚을 다 갚고 남는 몫을 주가와 견준다.",
        interpretation = "1보다 크면 시가총액보다 순유동자산이 많다는 뜻으로, 자산만으로도 "
                + "주가가 설명되는 드문 구간이다. 그레이엄의 원래 매수 기준은 1.5 이상이다. "
                + "대부분의 종목은 음수이며 그것이 정상이다 — 설비와 차입을 갖춘 회사는 "
                + "유동자산이 총부채를 넘지 않는다. 자산의 질(재고·매출채권이 장부가만큼 "
                + "받을 수 있는지)은 이 값이 답하지 못한다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        unit = "배",
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        decimalScale = 2,
        sortOrder = 28,
        bands = {
                @IndicatorMeta.Band(label = "그레이엄 기준 충족", from = 1.5),
                @IndicatorMeta.Band(label = "시총 이상", from = 1.0, to = 1.5),
                @IndicatorMeta.Band(label = "일부 뒷받침", from = 0, to = 1.0),
                @IndicatorMeta.Band(label = "순유동자산 음수", to = 0)
        }
)
public class NcavToMarketCalculator implements IndicatorCalculator {

    public static final String CODE = "NCAV_TO_MARKET";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        return new DataRequirement(1, 1, Set.of(MarketCapCalculator.CODE));
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.bySegment(context, NcavToMarketCalculator::pointsFrom);
    }

    /**
     * 그 봉 시점에 공시돼 있던 재무만 담긴 컨텍스트로 낸 시계열(ADR-0128).
     *
     * <p>재무 합산이 이 루프 <b>밖</b>에 있는 것이 옳다 — 이 메서드 자체가 한
     * 공시 구간에 대해 불리고, 구간 밖 봉의 값은 {@code bySegment}가 버린다.
     */
    private static List<IndicatorPoint> pointsFrom(IndicatorContext context) {
        FinancialMath.PeriodSeries series = FinancialMath.seriesOf(context);
        if (series.isEmpty()) {
            return List.of();
        }
        // 저량이다. 최근 분기 잔액 하나를 쓴다 — 4분기를 더하면 4배가 된다.
        FinancialFigures latest = series.latestPoint();
        BigDecimal currentAssets = latest.currentAssets();
        // 관문 켬 미국 행은 보통주보다 앞서는 청구권(우선주 · 메자닌)까지 뺀다 — 분모가 보통주 시가총액이다(탐색 PERSHARE-DEN-PLAN-1026.md v1.1).
        BigDecimal totalLiabilities = FinancialMath.claimsAheadOfCommon(latest);
        if (currentAssets == null || totalLiabilities == null) {
            return List.of();
        }
        BigDecimal ncav = currentAssets.subtract(totalLiabilities);

        NavigableMap<LocalDate, BigDecimal> marketCap =
                context.dependency(MarketCapCalculator.CODE);

        List<IndicatorPoint> points = new ArrayList<>(context.priceBars().size());
        for (PriceBar bar : context.priceBars()) {
            BigDecimal cap = marketCap.get(bar.tradeDate());
            if (cap == null || cap.signum() <= 0) {
                continue;
            }
            // 분자의 음수를 거르지 않는다. 음수 NCAV는 결손이 아니라 사실이고,
            // 거르면 "값이 없는 것"과 "자산 뒷받침이 없는 것"이 화면에서 같아진다.
            points.add(new IndicatorPoint(bar.tradeDate(),
                    ncav.divide(cap, FinancialMath.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }
}
