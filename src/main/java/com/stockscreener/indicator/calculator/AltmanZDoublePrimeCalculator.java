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
import java.math.RoundingMode;
import java.util.List;

/**
 * Altman Z''-Score — 원본 Z에서 매출 항을 빼고 시가총액을 자기자본 장부가로 바꾼 4변수 모델.
 *
 * <pre>
 * Z'' = 6.56·(운전자본/총자산) + 3.26·(이익잉여금/총자산)
 *     + 6.72·(EBIT/총자산)     + 1.05·(자기자본/총부채)
 * </pre>
 *
 * <p><b>원본과 갈리는 지점이 둘이고 뜻이 다르다.</b> 매출/총자산 항을 뺀 것은 <b>업종 간
 * 자산회전율 차이를 없애려는 것</b>이고, 그래서 이 모델은 제조업과 비제조업을 하나로 본다 —
 * 원본은 제조업 표본에서 나왔고 서비스·소프트웨어처럼 자산이 가벼운 기업에서 그 항이
 * 점수를 밀어 올린다. 시가총액을 장부가로 바꾼 것은 비상장 기업까지 덮으려던 것이라
 * 우리 유니버스에서는 부수적이다.
 *
 * <p><b>그래서 이 지표는 시가총액에 의존하지 않는다.</b> 원본이 봉마다 값을 다시 내는 것과
 * 달리 여기서는 네 항이 전부 재무에서 나오므로 <b>값 하나를 거래일에 펼친다.</b>
 *
 * <p>구간은 안전 2.6 이상 · 회색지대 1.1~2.6 · 부실 위험 1.1 미만이다. 원본의 3.0/1.8과
 * 다르며, 계수가 다르므로 <b>두 지표의 값을 같은 자로 비교하면 안 된다.</b>
 *
 * <p>유량과 저량을 섞지 않는다. EBIT는 4분기 합(TTM)이고 자산·부채·자본·운전자본·이익잉여금은
 * 최근 분기 <b>잔액</b>이다. 저량을 합산하면 4배가 되는데 값이 그럴듯해 드러나지 않는다.
 *
 * <p><b>자기자본은 비지배지분을 포함한 자본총계다</b>({@code total_equity}). 원문의
 * "book value of equity"가 지배주주지분인지 자본총계인지는 이 저장소가 판정한 적이 없고,
 * 지배주주지분은 KR·US 어느 쪽도 수집하지 않아 지금 선택지가 아니다.
 *
 * <p><b>업종 정의역은 원본과 같고, 판정도 원본의 것을 그대로 쓴다</b>
 * ({@link AltmanZCalculator#isInapplicable}). 금융업·보험업은 자본구조가 근본적으로 달라
 * 계수가 성립하지 않는데, 그 집합을 여기 복사하면 세 번째 사본이 되어 넓힐 때 갈린다.
 */
@Component
@IndicatorMeta(
        name = "Altman Z''-Score",
        description = "알트만 Z의 수정모델. 운전자본·이익잉여금·영업이익을 총자산으로, "
                + "자기자본 장부가를 총부채로 나눠 가중합한 부실 예측 점수. "
                + "매출 항을 빼 업종별 자산회전율 차이를 없앴으므로 제조업과 비제조업에 함께 쓴다. "
                + "금융업에는 적용하지 않는다.",
        interpretation = "2.6 이상이면 안전, 1.1 이하면 부실 위험 구간으로 본다. "
                + "그 사이는 회색지대다. 계수와 경계가 원본 Altman Z와 다르므로 "
                + "두 점수를 같은 자로 비교하지 말고 각자의 구간으로 읽는다. "
                + "원본이 안전이라 해도 이 점수가 낮은 종목이 있는데, 원본은 시가총액을 쓰고 "
                + "이 지표는 자기자본 장부가를 써서 주가가 장부가보다 훨씬 높은 회사에서 갈린다. "
                + "특히 구독료를 미리 받는 회사는 그 대금이 유동부채로 잡혀 운전자본이 음수가 되므로, "
                + "재무가 튼튼해도 이 점수만 낮게 나올 수 있다.",
        category = IndicatorCategory.FUNDAMENTAL,
        valueType = IndicatorValueType.RATIO,
        decimalScale = 2,
        direction = IndicatorMeta.Direction.HIGHER_IS_BETTER,
        sortOrder = 47,
        bands = {
                // 2.6 / 1.1은 Altman(1995)의 4변수 모델이 제시한 경계이며
                // interpretation이 이미 적어 둔 값이다.
                @IndicatorMeta.Band(label = "안전", from = 2.6),
                @IndicatorMeta.Band(label = "회색지대", from = 1.1, to = 2.6),
                @IndicatorMeta.Band(label = "부실 위험", to = 1.1)
        }
)
public class AltmanZDoublePrimeCalculator implements IndicatorCalculator {

    public static final String CODE = "ALTMAN_Z_DOUBLE_PRIME";

    private static final BigDecimal W_WORKING_CAPITAL = new BigDecimal("6.56");
    private static final BigDecimal W_RETAINED_EARNINGS = new BigDecimal("3.26");
    private static final BigDecimal W_EBIT = new BigDecimal("6.72");
    private static final BigDecimal W_EQUITY = new BigDecimal("1.05");

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public DataRequirement dataRequirement() {
        // 시세 1봉 + 재무 1년. 원본과 달리 MARKET_CAP에 의존하지 않는다 — 그 의존이
        // 미국에서 이 계열을 통째로 0행으로 만든 적이 있다(ADR-0097).
        return DataRequirement.priceAndFinancial(1, 1);
    }

    @Override
    public List<IndicatorPoint> calculate(IndicatorContext context) {
        return FinancialMath.byPublication(context, AltmanZDoublePrimeCalculator::valueOf);
    }

    /** 그 봉 시점에 공시돼 있던 재무로 낸 값. 없으면 {@code null}이다(ADR-0128). */
    private static BigDecimal valueOf(IndicatorContext context) {
        if (AltmanZCalculator.isInapplicable(context.industryDivisionKey())) {
            // 값을 낼 수 없는 것이 아니라 내면 안 되는 것이다. 뜻 없는 값이 필수 조건을
            // 통과하는 것이 이 계열의 가장 큰 위험이다.
            return null;
        }

        FinancialMath.PeriodSeries series = FinancialMath.seriesOf(context);
        if (series.isEmpty()) {
            return null;
        }
        FinancialFigures latest = series.latestPoint();

        BigDecimal totalAssets = latest.totalAssets();
        BigDecimal totalLiabilities = FinancialMath.totalLiabilitiesOf(latest);
        if (isUnusableDenominator(totalAssets) || isUnusableDenominator(totalLiabilities)) {
            return null;
        }

        // 저량 — 최근 분기 잔액.
        BigDecimal currentAssets = latest.currentAssets();
        BigDecimal currentLiabilities = latest.currentLiabilities();
        BigDecimal retainedEarnings = latest.retainedEarnings();
        BigDecimal equity = latest.totalEquity();
        // 유량 — 4분기 합. 원시 영업이익이 없으면 유도한다(ADR-0104).
        BigDecimal ebit = series.ttm(FinancialMath::operatingIncomeOf, 0);

        if (currentAssets == null || currentLiabilities == null
                || retainedEarnings == null || equity == null || ebit == null) {
            // 금융업이 대개 여기서 빠진다 — 유동/비유동을 구분하지 않는 재무상태표를 내기 때문이다.
            return null;
        }

        BigDecimal workingCapital = currentAssets.subtract(currentLiabilities);
        BigDecimal z = ratio(workingCapital, totalAssets).multiply(W_WORKING_CAPITAL)
                .add(ratio(retainedEarnings, totalAssets).multiply(W_RETAINED_EARNINGS))
                .add(ratio(ebit, totalAssets).multiply(W_EBIT))
                .add(ratio(equity, totalLiabilities).multiply(W_EQUITY));

        // 저장 컬럼이 numeric(24,8)이라 DB 값은 어차피 접히지만, computeAt이 내는 메모리 값은
        // 접히지 않은 채 시점 재현 저장본에 실린다. 두 경로가 갈리지 않게 여기서 맞춘다.
        return z.setScale(FinancialMath.SCALE, RoundingMode.HALF_UP);
    }

    /** 총자산·총부채가 0 이하면 Z'' 자체가 성립하지 않는다. 음수 총자산은 데이터 결함이다. */
    private static boolean isUnusableDenominator(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return numerator.divide(denominator, MovingAverages.MC);
    }
}
