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
 * <p>값 하나를 시세 날짜 전체에 펼친다({@link FinancialMath#spreadOverTradeDates}). 즉 과거
 * 날짜의 행도 <b>최신 시점에 계산한 중앙값</b>이며, 그것은 {@code OCF_SUM_5Y}를 비롯한 기존
 * 재무 지표와 같은 성질이다.
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

    /** 되돌아보는 거리. {@link EpsCagr5yCalculator}와 같은 창을 쓴다. */
    static final int LOOKBACK = EpsCagr5yCalculator.LOOKBACK;

    /**
     * 중앙값을 낼 최소 표본. 5년치 거래일의 절반이다.
     *
     * <p>표본이 적으면 중앙값이 특정 국면 하나를 가리키는데 값은 그대로 나온다 —
     * 「5년 중앙값」이라는 이름이 그때 거짓이 된다.
     */
    static final int MIN_SAMPLES = LOOKBACK / 2;

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
        List<FinancialFigures> quarters = context.quarterlyFigures();
        List<PriceBar> bars = context.priceBars();
        if (quarters.isEmpty() || bars.size() <= LOOKBACK) {
            return List.of();
        }

        int from = bars.size() - LOOKBACK - 1;
        List<Double> ratios = new ArrayList<>(LOOKBACK + 1);
        for (int i = from; i < bars.size(); i++) {
            PriceBar bar = bars.get(i);
            BigDecimal ttm = ttmKnownOn(quarters, bar.tradeDate());
            if (ttm == null || ttm.signum() <= 0) {
                // 적자 구간의 음수 PER은 중앙값을 끌어내려 「싸다」로 읽힌다.
                continue;
            }
            BigDecimal eps = FinancialMath.trailingEpsOn(context, bar, ttm, quarters);
            if (eps == null || eps.signum() <= 0) {
                continue;
            }
            ratios.add(bar.close()
                    .divide(eps, FinancialMath.SCALE, RoundingMode.HALF_UP)
                    .doubleValue());
        }
        if (ratios.size() < MIN_SAMPLES) {
            return List.of();
        }

        double[] values = new double[ratios.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = ratios.get(i);
        }
        BigDecimal median = BigDecimal.valueOf(SeriesMath.median(values))
                .setScale(FinancialMath.SCALE, RoundingMode.HALF_UP);
        return FinancialMath.spreadOverTradeDates(bars, median);
    }

    /**
     * 그 날짜에 <b>이미 공시돼 있던</b> 4개 분기의 순이익 합. 위 IMPORTANT가 이 메서드의 이유다.
     *
     * <p>분기는 {@code period_end} 내림차순이므로, 공시일이 지난 첫 분기부터 넷을 더한다.
     * 그 시점에 넷이 모이지 않으면 null이다 — 셋으로 TTM을 만들면 이익이 4분의 1 작아지고
     * PER이 그만큼 부풀어 오른다.
     */
    private static BigDecimal ttmKnownOn(List<FinancialFigures> quarters, LocalDate asOf) {
        for (int j = 0; j < quarters.size(); j++) {
            LocalDate published = quarters.get(j).publishedAt();
            if (published != null && !published.isAfter(asOf)) {
                return FinancialMath.sumFourQuarters(quarters, FinancialFigures::netIncome, j);
            }
        }
        return null;
    }
}
