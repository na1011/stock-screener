package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 밸류에이션 축을 넓히는 지표 둘. 출처는 각 계산기의 javadoc에 있다.
 *
 * <p>두 지표가 <b>일부러 다른 주식수 경로</b>를 탄다는 것이 이 파일에서 가장 중요한 검사다 —
 * {@code PSR}은 주당 배수라 {@code PER}·{@code PBR}과 같은 {@link FinancialMath#sharesOf}를
 * 쓰고, {@code NCAV_TO_MARKET}은 시가총액 대비라 {@code MARKET_CAP}에 의존한다.
 */
class ValuationIndicatorsTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 1, 2);

    @Nested
    @DisplayName("PSR — 주가 ÷ 주당매출(TTM)")
    class Psr {

        private final PsrCalculator calculator = new PsrCalculator();

        @Test
        @DisplayName("분기 매출 4개를 더해 주당매출을 만들고 주가로 나눈다")
        void computesFromTtmRevenue() {
            // 분기 매출 250 × 4 = 1000, 주식수 100 → 주당매출 10. 주가 20 → PSR 2
            IndicatorContext context = context(BigDecimal.valueOf(20),
                    quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)));

            List<IndicatorPoint> points = calculator.calculate(context);

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("IMPORTANT — 매출은 유량이라 4분기를 더한다. 한 분기만 쓰면 4배로 비싸 보인다")
        void sumsFourQuartersNotOne() {
            IndicatorContext context = context(BigDecimal.valueOf(20),
                    quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)));

            assertThat(calculator.calculate(context).get(0).value())
                    .as("한 분기(250)만 쓰면 주당매출 2.5가 되어 PSR이 8로 나온다")
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("분기가 4개 미만이면 계산하지 않는다")
        void producesNothingWithoutFourQuarters() {
            assertThat(calculator.calculate(context(BigDecimal.valueOf(20),
                    quarters(3, BigDecimal.valueOf(250), BigDecimal.valueOf(100)))))
                    .isEmpty();
        }

        @Test
        @DisplayName("적자여도 값을 낸다 — PER이 값을 못 내는 구간을 메우는 것이 이 지표의 존재 이유다")
        void producesValueEvenWhenLossMaking() {
            List<FinancialFigures> quarters = new ArrayList<>(
                    quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)));
            for (int i = 0; i < quarters.size(); i++) {
                quarters.set(i, FinancialFiguresFixture.from(quarters.get(i))
                        .netIncome(BigDecimal.valueOf(-999)).build());
            }

            assertThat(calculator.calculate(context(BigDecimal.valueOf(20), quarters)))
                    .as("순이익 부호는 PSR과 무관하다")
                    .hasSize(1);
        }

        @Test
        @DisplayName("매출이 0 이하면 값을 내지 않는다 — 매출 개념이 없는 업종이 그 자리다")
        void producesNothingWithoutRevenue() {
            assertThat(calculator.calculate(context(BigDecimal.valueOf(20),
                    quarters(4, BigDecimal.ZERO, BigDecimal.valueOf(100)))))
                    .isEmpty();
        }

        @Test
        @DisplayName("IMPORTANT — 원주가(close)를 쓴다. 수정주가는 시점의 발행주식수와 짝이 맞지 않는다")
        void usesUnadjustedClose() {
            PriceBar bar = new PriceBar(TRADE_DATE,
                    BigDecimal.valueOf(20), BigDecimal.valueOf(20), BigDecimal.valueOf(20),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(4), 1000L, null, null);

            IndicatorContext context = new IndicatorContext(1L, List.of(bar), List.of(),
                    quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)), null);

            assertThat(calculator.calculate(context).get(0).value())
                    .as("adjClose(4)로 계산했다면 0.4가 나온다")
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("PER·PBR과 같은 주식수 경로를 쓴다 — 시세를 우선하고 재무로 폴백한다")
        void sharesPathMatchesPerAndPbr() {
            // 재무는 100주, 시세는 200주. 시세를 써야 주당매출 5 → PSR 4다.
            PriceBar bar = new PriceBar(TRADE_DATE,
                    BigDecimal.valueOf(20), BigDecimal.valueOf(20), BigDecimal.valueOf(20),
                    BigDecimal.valueOf(20), BigDecimal.valueOf(20), 1000L, null,
                    BigDecimal.valueOf(200));

            IndicatorContext context = new IndicatorContext(1L, List.of(bar), List.of(),
                    quarters(4, BigDecimal.valueOf(250), BigDecimal.valueOf(100)), null);

            assertThat(calculator.calculate(context).get(0).value())
                    .as("재무 쪽 100주를 쓰면 2가 나온다 — 그러면 PER·PBR과 기저가 갈린다")
                    .isEqualByComparingTo("4");
        }
    }

    @Nested
    @DisplayName("NCAV_TO_MARKET — (유동자산 − 총부채) ÷ 시가총액")
    class NcavToMarket {

        private final NcavToMarketCalculator calculator = new NcavToMarketCalculator();

        @Test
        @DisplayName("유동자산에서 총부채를 빼고 시가총액으로 나눈다")
        void computesFromCurrentAssetsMinusTotalLiabilities() {
            // 유동자산 5000 − 총부채 3000 = 2000. 시총 1000 → 2배
            assertThat(calculator.calculate(contextWith(
                    BigDecimal.valueOf(5000), BigDecimal.valueOf(3000), BigDecimal.valueOf(1000)))
                    .get(0).value())
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("IMPORTANT — 유동부채가 아니라 총부채를 뺀다. 유동부채만 빼면 순운전자본이다")
        void subtractsTotalLiabilitiesNotCurrentOnes() {
            List<FinancialFigures> quarters = List.of(
                    FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31))
                            .currentAssets(BigDecimal.valueOf(5000))
                            .currentLiabilities(BigDecimal.valueOf(1000))
                            .totalLiabilities(BigDecimal.valueOf(3000))
                            .build());

            assertThat(calculator.calculate(marketCapContext(quarters, BigDecimal.valueOf(1000)))
                    .get(0).value())
                    .as("유동부채(1000)를 뺐다면 4가 나온다 — 그러면 그레이엄 기준 충족이 무더기로 나온다")
                    .isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("순유동자산이 음수여도 값을 낸다 — 국내 대형주 대부분이 그렇고 그것이 사실이다")
        void producesNegativeValues() {
            assertThat(calculator.calculate(contextWith(
                    BigDecimal.valueOf(1000), BigDecimal.valueOf(3000), BigDecimal.valueOf(1000)))
                    .get(0).value())
                    .as("거르면 '값이 없는 것'과 '자산 뒷받침이 없는 것'이 화면에서 같아진다")
                    .isEqualByComparingTo("-2");
        }

        @Test
        @DisplayName("유동자산이 없으면 계산하지 않는다 — 유동/비유동을 구분하지 않는 업종이 그 자리다")
        void producesNothingWithoutCurrentAssets() {
            assertThat(calculator.calculate(contextWith(
                    null, BigDecimal.valueOf(3000), BigDecimal.valueOf(1000))))
                    .isEmpty();
        }

        @Test
        @DisplayName("시가총액이 없는 날은 건너뛴다 — 다시 계산하지 않고 의존으로만 받는다")
        void skipsDatesWithoutMarketCap() {
            List<FinancialFigures> quarters = List.of(
                    FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31))
                            .currentAssets(BigDecimal.valueOf(5000))
                            .totalLiabilities(BigDecimal.valueOf(3000))
                            .build());

            assertThat(calculator.calculate(new IndicatorContext(1L,
                    List.of(priceBar(BigDecimal.valueOf(20))), List.of(), quarters,
                    Map.of(MarketCapCalculator.CODE, new TreeMap<LocalDate, BigDecimal>()))))
                    .isEmpty();
        }


        private IndicatorContext contextWith(BigDecimal currentAssets,
                                             BigDecimal totalLiabilities,
                                             BigDecimal cap) {
            List<FinancialFigures> quarters = List.of(
                    FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31))
                            .currentAssets(currentAssets)
                            .totalLiabilities(totalLiabilities)
                            .build());
            return marketCapContext(quarters, cap);
        }

        private IndicatorContext marketCapContext(List<FinancialFigures> quarters, BigDecimal cap) {
            NavigableMap<LocalDate, BigDecimal> marketCap = new TreeMap<>();
            marketCap.put(TRADE_DATE, cap);
            return new IndicatorContext(1L, List.of(priceBar(BigDecimal.valueOf(20))),
                    List.of(), quarters, Map.of(MarketCapCalculator.CODE, marketCap));
        }
    }

    private static IndicatorContext context(BigDecimal close, List<FinancialFigures> quarters) {
        return new IndicatorContext(1L, List.of(priceBar(close)), List.of(), quarters, null);
    }

    private static PriceBar priceBar(BigDecimal close) {
        return new PriceBar(TRADE_DATE, close, close, close, close, close, 1000L, null, null);
    }

    private static List<FinancialFigures> quarters(int count, BigDecimal revenue, BigDecimal shares) {
        List<FinancialFigures> list = new ArrayList<>(count);
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < count; i++) {
            list.add(FinancialFiguresFixture.quarter(end.minusMonths(3L * i))
                    .revenue(revenue)
                    .sharesOutstanding(shares)
                    .build());
        }
        return list;
    }
}
