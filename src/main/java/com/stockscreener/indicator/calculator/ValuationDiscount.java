package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;

/**
 * 「자기 5년 중앙값 대비 몇 % 싼가」. {@code *_MEDIAN_5Y}가 <b>참조값</b>이라 충족/불충족까지만
 * 되던 것을 <b>정렬·배점이 가능한 연속값</b>으로 바꾼다.
 *
 * <p>조건으로는 원래도 {@code PER < PER_MEDIAN_5Y}를 걸 수 있었다(조건 우변에 지표를 놓는 길이
 * 열려 있고 린치 고속성장주가 그렇게 쓴다). <b>그것으로 안 되는 것은 「얼마나」다</b> — 5% 싼
 * 종목과 40% 싼 종목이 같은 1점을 받는다.
 *
 * <h2>IMPORTANT — 시간축을 이렇게 정한 이유</h2>
 *
 * <p>중앙값은 {@link ValuationHistory}가 <b>최신 시점에 한 번 계산해 창 전체에 편 값</b>이다.
 * 그런데 비교 대상인 현재 배수는 봉마다 다르다. 둘을 봉마다 짝지으면 <b>과거 일자 행이 미래
 * 5년을 알고 만든 중앙값과 견줘진다</b>(look-ahead). 게다가 {@code PbrCalculator}는 최신 자기자본
 * 하나를 모든 봉에 쓰는데 {@code PBR_MEDIAN_5Y}는 봉마다 다시 세우므로, 봉마다 짝지으면
 * <b>분자와 분모의 기저가 서로 다르다.</b>
 *
 * <p>그래서 <b>둘 다 값이 있는 가장 최근 봉에서 한 번 계산해 펼친다.</b> 그 봉에서는 「현재값의
 * 기저」와 「중앙값의 마지막 표본의 기저」가 같은 재무라 어긋남이 원리적으로 없다. 저장되는
 * 180일치가 전부 같은 값이 되는데, 그것은 {@code PER_MEDIAN_5Y}·{@code OCF_SUM_5Y}·
 * {@code RETAINED_TO_MARKET_4Y}가 <b>이미 갖는 성질</b>이다 — 새 성질을 들이지 않는다.
 *
 * <p><b>MUST NOT: 저장된 과거 행을 그 시점의 판단으로 읽지 않는다.</b> 이 값은 최신 일자 기준이고
 * 과거 행은 그 사본이다. <b>그래서 백테스트에 쓰면 look-ahead가 있다</b> — ADR-0071이 그 경로를
 * 열어 두었으므로 여기 적지 않으면 다음 세션이 시계열로 읽는다.
 */
final class ValuationDiscount {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ValuationDiscount() {
    }

    /**
     * {@code (중앙값 − 현재) ÷ 중앙값 × 100}. 양수면 자기 이력보다 싸다.
     *
     * @param baseCode   현재 배수를 내는 지표({@code PER}·{@code PBR}·{@code PSR})
     * @param medianCode 그 배수의 5년 중앙값 지표
     */
    static List<IndicatorPoint> of(IndicatorContext context, String baseCode, String medianCode) {
        List<PriceBar> bars = context.priceBars();
        if (bars.isEmpty()) {
            return List.of();
        }
        NavigableMap<LocalDate, BigDecimal> base = context.dependency(baseCode);
        NavigableMap<LocalDate, BigDecimal> median = context.dependency(medianCode);

        // 둘 다 있는 가장 최근 봉을 찾는다. 뒤에서부터 보는 것이 요점이다 —
        // 앞에서 찾으면 창의 첫 봉이 잡히고 그것은 「지금 싼가」가 아니다.
        for (int i = bars.size() - 1; i >= 0; i--) {
            LocalDate date = bars.get(i).tradeDate();
            BigDecimal now = base.get(date);
            BigDecimal reference = median.get(date);
            if (now == null || reference == null || reference.signum() <= 0) {
                continue;
            }
            BigDecimal discount = reference.subtract(now)
                    .multiply(HUNDRED)
                    .divide(reference, FinancialMath.SCALE, RoundingMode.HALF_UP);
            return FinancialMath.spreadOverTradeDates(bars, discount);
        }
        return List.of();
    }
}
