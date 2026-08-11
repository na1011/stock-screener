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
 * Altman Z-Score — 다섯 재무비율을 가중합한 부실 예측 지표.
 *
 * <pre>
 * Z = 1.2·(운전자본/총자산) + 1.4·(이익잉여금/총자산) + 3.3·(EBIT/총자산)
 *   + 0.6·(시가총액/총부채) + 1.0·(매출/총자산)
 * </pre>
 *
 * <p>개별 비율을 각각 통과하면서도 <b>전체적으로 위험한 조합</b>을 잡는 것이 존재 이유다.
 * 부채비율이 무난하고 흑자이면서도 운전자본이 마르고 이익잉여금이 얇은 기업이 그런 경우다.
 *
 * <p>IMPORTANT — <b>금융업·보험업에는 적용할 수 없다.</b> 자본구조가 근본적으로 달라 계수
 * 자체가 성립하지 않는다. KSIC 중분류 <b>65(보험)·66(금융서비스)</b>를 명시적으로 제외한다.
 *
 * <p><b>64(금융업)는 제외하지 않는다.</b> 이름과 달리 그 중분류는 <b>지주회사 버킷을 겸한다</b> —
 * {@code 64992}에 신한지주·KB금융 같은 금융지주와 LG·SK·GS 같은 비금융 사업지주가 함께 있고,
 * 3자리 {@code 649}에도 미래에셋벤처투자와 효성·HDC가 섞인다. <b>코드로는 가를 수 없다.</b>
 * 350종목 실측에서 64·65·66의 51종목 중 값이 나온 22종목이 21개까지 비금융 사업지주였고,
 * 진짜 금융사는 아래 유동자산 결손으로 이미 전부 빠졌다. 64를 통째로 자르면 <b>얻는 것은
 * 미래 방어뿐이고 잃는 것은 적용 가능한 21종목</b>이다(ADR-0044).
 *
 * <p>그 유동자산 결손이 남은 방어다 — 금융업은 유동/비유동을 구분하지 않는 재무상태표를 내므로
 * {@code currentAssets}가 비고 값이 계산되지 않는다. <b>의도한 방어가 아니라 부작용</b>이므로
 * 이 지표를 조건으로 쓰는 화면은 그 한계를 함께 알려야 한다.
 *
 * <p>유량과 저량을 섞지 않는다. 매출·EBIT는 4분기 합(TTM)이고, 자산·부채·운전자본·이익잉여금은
 * 최근 분기 <b>잔액</b>이다. 저량을 합산하면 4배가 되는데 값이 그럴듯해 드러나지 않는다.
 *
 * <p>시가총액 항 때문에 이 지표는 <b>날짜마다 값이 다르다.</b> 다른 재무 지표처럼 하나를 펼치지
 * 않고 봉마다 계산한다.
 */
@Component
@IndicatorMeta(
        name = "Altman Z-Score",
        description = "운전자본·이익잉여금·영업이익·시가총액·매출을 총자산과 총부채로 나눠 가중합한 부실 예측 점수. "
                + "제조·유통업 기준이며 금융업에는 적용하지 않는다.",
        interpretation = "3.0 이상이면 안전, 1.8 이하면 부실 위험 구간으로 본다. "
                + "그 사이는 회색지대다. 개별 비율이 모두 무난해도 이 값이 낮으면 "
                + "조합이 취약하다는 뜻이므로 무엇이 낮은지 확인할 필요가 있다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 46,
        bands = {
                // 3.0 / 1.8은 원 논문 이래 통용되는 경계이며 interpretation이 이미 적어 둔 값이다.
                @IndicatorMeta.Band(label = "안전", from = 3.0),
                @IndicatorMeta.Band(label = "회색지대", from = 1.8, to = 3.0),
                @IndicatorMeta.Band(label = "부실 위험", to = 1.8)
        }
)
public class AltmanZCalculator implements IndicatorCalculator {

    public static final String CODE = "ALTMAN_Z";

    /**
     * 이 지표가 성립하지 않는 KSIC 중분류.
     *
     * <p>64(금융업)가 빠져 있는 것이 의도다 — 위 javadoc의 이유로 그 중분류는 지주회사를
     * 함께 담아 코드로 가를 수 없다.
     *
     * <p>업종을 <b>모르는</b> 종목은 제외하지 않는다. 제외 집합이 좁고 특정적이라
     * "모르면 뺀다"로 두면 <b>64인 것을 아는 종목은 넣으면서 아무것도 모르는 종목은 빼는</b>
     * 비일관이 된다. 업종이 비어 있는 종목이 프리셋을 통과하지는 않는다 — 업종 상대 조건이
     * 그 종목에 값을 내지 않아 판정불가로 먼저 걸린다(ADR-0044).
     */
    private static final Set<String> INAPPLICABLE_DIVISIONS = Set.of("65", "66");

    private static final BigDecimal W_WORKING_CAPITAL = new BigDecimal("1.2");
    private static final BigDecimal W_RETAINED_EARNINGS = new BigDecimal("1.4");
    private static final BigDecimal W_EBIT = new BigDecimal("3.3");
    private static final BigDecimal W_MARKET_CAP = new BigDecimal("0.6");
    private static final BigDecimal W_SALES = BigDecimal.ONE;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 시세 1봉 + 재무 1년 + 시가총액 의존. 세 가지를 동시에 쓰는 팩토리가 없어 정본 생성자를 쓴다.
        return new DataRequirement(1, 1, Set.of(MarketCapCalculator.CODE));
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        String division = context.industryDivision();
        // null 검사를 밖에 두는 것이 의도다. Set.of(...)는 contains(null)에 NPE를 던지므로
        // 업종 미상을 어떻게 다룰지가 여기서 반드시 드러난다 — 조용히 제외되지 않는다.
        if (division != null && INAPPLICABLE_DIVISIONS.contains(division)) {
            // 값을 낼 수 없는 것이 아니라 내면 안 되는 것이다. 보험사의 Z는 계산은 되지만
            // 뜻이 없고, 뜻 없는 값이 필수 조건을 통과하는 것이 이 지표의 가장 큰 위험이다.
            return List.of();
        }

        List<FinancialFigures> quarters = context.quarterlyFigures();
        if (quarters.isEmpty()) {
            return List.of();
        }
        FinancialFigures latest = quarters.get(0);

        BigDecimal totalAssets = latest.totalAssets();
        BigDecimal totalLiabilities = latest.totalLiabilities();
        if (isUnusableDenominator(totalAssets) || isUnusableDenominator(totalLiabilities)) {
            return List.of();
        }

        // 저량 — 최근 분기 잔액.
        BigDecimal currentAssets = latest.currentAssets();
        BigDecimal currentLiabilities = latest.currentLiabilities();
        BigDecimal retainedEarnings = latest.retainedEarnings();
        // 유량 — 4분기 합.
        BigDecimal ebit = FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::operatingIncome);
        BigDecimal sales = FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::revenue);

        if (currentAssets == null || currentLiabilities == null
                || retainedEarnings == null || ebit == null || sales == null) {
            // 금융업이 대개 여기서 빠진다 — 유동/비유동을 구분하지 않는 재무상태표를 내기 때문이다.
            return List.of();
        }

        BigDecimal workingCapital = currentAssets.subtract(currentLiabilities);

        // 시가총액을 제외한 네 항은 날짜와 무관하다. 봉마다 다시 계산할 이유가 없다.
        BigDecimal fixedPart = ratio(workingCapital, totalAssets).multiply(W_WORKING_CAPITAL)
                .add(ratio(retainedEarnings, totalAssets).multiply(W_RETAINED_EARNINGS))
                .add(ratio(ebit, totalAssets).multiply(W_EBIT))
                .add(ratio(sales, totalAssets).multiply(W_SALES));

        NavigableMap<LocalDate, BigDecimal> marketCap = context.dependency(MarketCapCalculator.CODE);
        List<IndicatorPoint> points = new ArrayList<>();
        for (PriceBar bar : context.priceBars()) {
            BigDecimal cap = marketCap.get(bar.tradeDate());
            if (cap == null) {
                continue;
            }
            BigDecimal z = fixedPart.add(ratio(cap, totalLiabilities).multiply(W_MARKET_CAP));
            points.add(new IndicatorPoint(bar.tradeDate(),
                    z.setScale(FinancialMath.SCALE, RoundingMode.HALF_UP)));
        }
        return points;
    }

    /** 총자산·총부채가 0 이하면 Z 자체가 성립하지 않는다. 음수 총자산은 데이터 결함이다. */
    private static boolean isUnusableDenominator(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, MovingAverages.MC);
    }
}
