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
 * 중장기 스윙 프리셋이 쓰는 신규 지표들.
 *
 * <p>여기 모은 이유는 이 지표들이 <b>같은 종류의 실수</b>를 공유하기 때문이다 — 원주가와
 * 수정주가를 섞는 것, 축척에 의존하는 값을 그대로 내는 것, 없는 값을 0으로 메우는 것.
 * 셋 다 값이 그럴듯해서 화면으로는 드러나지 않는다.
 */
class SwingIndicatorsTest {

    private static final LocalDate START = LocalDate.of(2022, 1, 3);

    /**
     * 재무를 쓰는 픽스처의 봉 시작일. <b>그 재무가 공시된 뒤</b>여야 한다(ADR-0128).
     *
     * <p>지표가 봉마다 「그 시점에 공시돼 있던」 재무로 계산하므로 봉이 공시일보다 앞이면
     * 값을 내지 않는 것이 옳다. {@code FinancialFiguresFixture}가 공시일을
     * {@code periodEnd + 2개월}로 둔다. 예전에는 스냅샷 하나를 창 전체에 펴서
     * <b>봉이 몇 해 앞이어도 값이 나왔다.</b>
     */
    private static final LocalDate FINANCIAL_START = LocalDate.of(2026, 3, 2);

    @Nested
    @DisplayName("시가총액")
    class MarketCap {

        private final MarketCapCalculator calculator = new MarketCapCalculator();

        @Test
        @DisplayName("원주가를 쓴다 — 수정주가를 쓰면 분할 이전 구간이 통째로 어긋난다")
        void usesUnadjustedClose() {
            // 원주가 2,000 / 수정주가 1,000. 주식수 100이면 원주가 기준 200,000이다.
            PriceBar bar = new PriceBar(START,
                    bd(2000), bd(2000), bd(2000), bd(2000), bd(1000), 10L, null, bd(100));

            List<IndicatorPoint> points = calculator.calculate(contextOf(List.of(bar)));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("200000");
        }

        @Test
        @DisplayName("주식수가 없는 봉은 값을 내지 않는다 — 0이면 어떤 하한 조건에도 안 걸린다")
        void skipsBarsWithoutShares() {
            PriceBar bar = new PriceBar(START,
                    bd(2000), bd(2000), bd(2000), bd(2000), bd(2000), 10L, null, null);

            assertThat(calculator.calculate(contextOf(List.of(bar)))).isEmpty();
        }
    }

    @Nested
    @DisplayName("200일선 60일 기울기 — 정규화가 이 지표의 존재 이유다")
    class Slope {

        private final Sma200Slope60Calculator calculator = new Sma200Slope60Calculator();

        @Test
        @DisplayName("IMPORTANT — 주가 수준이 달라도 같은 값이다. 원시 기울기를 내면 여기서 깨진다")
        void isScaleInvariant() {
            // 같은 비율로 오르는 두 시계열. 절대 기울기는 100배 차이지만 상승률은 같다.
            List<IndicatorPoint> cheap = calculator.calculate(withSma(risingSma(1_000, 1.001)));
            List<IndicatorPoint> expensive = calculator.calculate(withSma(risingSma(100_000, 1.001)));

            assertThat(cheap).isNotEmpty();
            assertThat(cheap).hasSameSizeAs(expensive);
            assertThat(cheap.getLast().value())
                    .isEqualByComparingTo(expensive.getLast().value());
        }

        @Test
        @DisplayName("상승 추세는 양수, 하락 추세는 음수 — 부호가 게이트 조건의 전부다")
        void signFollowsTrend() {
            assertThat(calculator.calculate(withSma(risingSma(10_000, 1.001)))
                    .getLast().value().signum()).isEqualTo(1);
            assertThat(calculator.calculate(withSma(risingSma(10_000, 0.999)))
                    .getLast().value().signum()).isEqualTo(-1);
        }

        @Test
        @DisplayName("완전한 횡보는 0이다 — 노이즈 없는 구간에서 우상향으로 읽히면 안 된다")
        void flatIsZero() {
            assertThat(calculator.calculate(withSma(risingSma(10_000, 1.0)))
                    .getLast().value()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("창을 채우지 못하면 값을 내지 않는다")
        void needsFullWindow() {
            NavigableMap<LocalDate, BigDecimal> sma = new TreeMap<>();
            for (int i = 0; i < Sma200Slope60Calculator.WINDOW - 1; i++) {
                sma.put(START.plusDays(i), bd(10_000));
            }
            assertThat(calculator.calculate(withSma(sma))).isEmpty();
        }
    }

    @Nested
    @DisplayName("200일선 1개월 변화율")
    class Momentum {

        private final Sma200Momentum21Calculator calculator = new Sma200Momentum21Calculator();

        @Test
        @DisplayName("21칸 전과 비교한다 — 달력이 아니라 거래일이다")
        void comparesAgainstTwentyOneBarsAgo() {
            NavigableMap<LocalDate, BigDecimal> sma = new TreeMap<>();
            for (int i = 0; i <= Sma200Momentum21Calculator.LOOKBACK; i++) {
                // 0번은 100, 나머지는 110. 21번째 시점에서 보면 21칸 전이 100이다.
                sma.put(START.plusDays(i), i == 0 ? bd(100) : bd(110));
            }

            List<IndicatorPoint> points = calculator.calculate(withSma(sma));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("10");
        }
    }

    @Nested
    @DisplayName("52주 고·저 대비")
    class FiftyTwoWeek {

        private final PctFrom52wLowCalculator low = new PctFrom52wLowCalculator();
        private final PctFrom52wHighCalculator high = new PctFrom52wHighCalculator();

        @Test
        @DisplayName("창 안의 최저·최고를 찾는다 — 덱이 미끄러져도 결과가 전수 탐색과 같아야 한다")
        void findsExtremesInWindow() {
            List<PriceBar> bars = flat(FiftyTwoWeekRange.WINDOW, 1_000);
            // 창 한가운데에 저점 500과 고점 2,000을 심는다.
            bars.set(100, bar(START.plusDays(100), 1_000, 1_000, 500));
            bars.set(150, bar(START.plusDays(150), 1_000, 2_000, 1_000));

            assertThat(low.calculate(contextOf(bars)).getLast().value())
                    .isEqualByComparingTo("100");   // 1000 / 500 - 1
            assertThat(high.calculate(contextOf(bars)).getLast().value())
                    .isEqualByComparingTo("-50");   // 1000 / 2000 - 1
        }

        @Test
        @DisplayName("창을 벗어난 극값은 잊는다 — 앞에서 버리지 않으면 영원히 저점이 남는다")
        void forgetsExtremesOutsideWindow() {
            List<PriceBar> bars = flat(FiftyTwoWeekRange.WINDOW + 1, 1_000);
            bars.set(0, bar(START, 1_000, 1_000, 100));

            List<IndicatorPoint> points = low.calculate(contextOf(bars));

            assertThat(points).hasSize(2);
            assertThat(points.get(0).value()).isEqualByComparingTo("900");  // 저점 100이 창 안
            assertThat(points.get(1).value()).isEqualByComparingTo("0");    // 창 밖으로 빠졌다
        }

        @Test
        @DisplayName("IMPORTANT — 고가·저가를 수정 축척으로 맞춘다. 분할 구간에 가짜 극값이 생기면 안 된다")
        void scalesRawHighLow() {
            List<PriceBar> bars = flat(FiftyTwoWeekRange.WINDOW, 1_000);
            // 분할 전 봉: 원주가 10,000이지만 수정종가는 1,000이다(10:1 분할).
            // 축척을 맞추지 않으면 저가 10,000이 그대로 들어와 "최고가"가 10배로 잡힌다.
            bars.set(50, new PriceBar(START.plusDays(50),
                    bd(10_000), bd(10_000), bd(10_000), bd(10_000), bd(1_000), 10L, null, null));

            assertThat(high.calculate(contextOf(bars)).getLast().value())
                    .as("수정 축척으로 맞췄다면 전 구간이 1,000이라 낙폭이 0이다")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("창을 채우지 못하면 값을 내지 않는다")
        void needsFullWindow() {
            assertThat(low.calculate(contextOf(flat(FiftyTwoWeekRange.WINDOW - 1, 1_000)))).isEmpty();
        }
    }

    @Nested
    @DisplayName("60일 평균 거래대금")
    class Turnover {

        private final Turnover60Calculator calculator = new Turnover60Calculator();

        @Test
        @DisplayName("실측 거래대금의 평균이다")
        void averagesTradeValue() {
            List<PriceBar> bars = withTradeValue(Turnover60Calculator.WINDOW, 1_000_000);

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("1000000");
        }

        @Test
        @DisplayName("창에 거래대금이 빈 날이 하나라도 있으면 값을 안 낸다 — 빼고 평균하면 짧은 창의 평균이 60일 평균으로 나간다")
        void yieldsNothingWhenAnyDayInWindowIsMissing() {
            List<PriceBar> bars = withTradeValue(Turnover60Calculator.WINDOW + 1, 1_000_000);
            // 첫 봉만 비운다 — 그 봉이 든 첫 창은 값이 없고, 그 봉이 빠진 둘째 창은 값이 난다.
            PriceBar b = bars.get(0);
            bars.set(0, new PriceBar(b.tradeDate(), b.open(), b.high(), b.low(),
                    b.close(), b.adjClose(), b.volume(), null, b.listedShares()));

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).tradeDate()).isEqualTo(bars.get(Turnover60Calculator.WINDOW).tradeDate());
            assertThat(points.get(0).value()).isEqualByComparingTo("1000000");
        }

        @Test
        @DisplayName("거래가 없던 날의 0은 값이다 — 정지 봉은 0으로 와서 평균을 낮춘다")
        void zeroIsAValueNotAGap() {
            List<PriceBar> bars = withTradeValue(Turnover60Calculator.WINDOW, 1_000_000);
            for (int i = 0; i < Turnover60Calculator.WINDOW / 2; i++) {
                PriceBar z = bars.get(i);
                bars.set(i, new PriceBar(z.tradeDate(), z.open(), z.high(), z.low(),
                        z.close(), z.adjClose(), 0L, java.math.BigDecimal.ZERO, z.listedShares()));
            }

            assertThat(calculator.calculate(contextOf(bars)).get(0).value()).isEqualByComparingTo("500000");
        }

        @Test
        @DisplayName("창 전체에 거래대금이 없으면 값을 내지 않는다 — 소스가 주지 않는 시장이 그렇다")
        void yieldsNothingWithoutAnyTradeValue() {
            assertThat(calculator.calculate(contextOf(flat(Turnover60Calculator.WINDOW, 1_000))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("3년 주식수 증가율")
    class SharesGrowth {

        private final SharesGrowth3yCalculator calculator = new SharesGrowth3yCalculator();

        @Test
        @DisplayName("750거래일 전과 비교한다")
        void comparesAgainstThreeYearsAgo() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i <= SharesGrowth3yCalculator.LOOKBACK; i++) {
                BigDecimal shares = i == 0 ? bd(1_000) : bd(1_050);
                bars.add(new PriceBar(START.plusDays(i),
                        bd(100), bd(100), bd(100), bd(100), bd(100), 10L, null, shares));
            }

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("이력이 3년에 못 미치면 값을 내지 않는다 — 상장 3년 미만이 여기서 빠진다")
        void needsThreeYearsOfHistory() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < SharesGrowth3yCalculator.LOOKBACK; i++) {
                bars.add(new PriceBar(START.plusDays(i),
                        bd(100), bd(100), bd(100), bd(100), bd(100), 10L, null, bd(1_000)));
            }

            assertThat(calculator.calculate(contextOf(bars))).isEmpty();
        }
    }

    @Nested
    @DisplayName("파생 비율 지표")
    class DerivedRatios {

        @Test
        @DisplayName("이격도는 수정종가 ÷ SMA200이다")
        void disparityDividesAdjCloseBySma() {
            NavigableMap<LocalDate, BigDecimal> sma = new TreeMap<>();
            sma.put(START, bd(1_000));
            PriceBar bar = new PriceBar(START, bd(1_250), bd(1_250), bd(1_250),
                    bd(1_250), bd(1_250), 10L, null, null);

            List<IndicatorPoint> points = new Disparity200Calculator().calculate(
                    new IndicatorContext(1L, List.of(bar), List.of(), List.of(),
                            Map.of(Sma200Calculator.CODE, sma)));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("1.25");
        }

        @Test
        @DisplayName("변동성 비율은 ATR ÷ 수정종가 × 100이다 — ATR과 달리 종목 간 비교가 된다")
        void atrPctNormalizesByPrice() {
            NavigableMap<LocalDate, BigDecimal> atr = new TreeMap<>();
            atr.put(START, bd(40));
            PriceBar bar = new PriceBar(START, bd(1_000), bd(1_000), bd(1_000),
                    bd(1_000), bd(1_000), 10L, null, null);

            List<IndicatorPoint> points = new AtrPct14Calculator().calculate(
                    new IndicatorContext(1L, List.of(bar), List.of(), List.of(),
                            Map.of(Atr14Calculator.CODE, atr)));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).value()).isEqualByComparingTo("4");
        }
    }

    @Nested
    @DisplayName("흑자 분기 수")
    class ProfitableQuarters {

        private final ProfitableQuarters4Calculator calculator = new ProfitableQuarters4Calculator();

        @Test
        @DisplayName("영업이익이 0보다 큰 분기를 센다")
        void countsPositiveOperatingIncome() {
            List<IndicatorPoint> points = calculator.calculate(financialContext(
                    quarters(300, -50, 200, 100), List.of()));

            assertThat(points).isNotEmpty();
            assertThat(points.getFirst().value()).isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("IMPORTANT — 분기가 4개에 못 미치면 값을 내지 않는다. 있는 것만 세면 이력이 짧을수록 잘 통과한다")
        void needsFourQuarters() {
            assertThat(calculator.calculate(financialContext(quarters(100, 100), List.of()))).isEmpty();
        }

        @Test
        @DisplayName("공시가 빠진 분기가 있으면 값을 내지 않는다 — 없는 것을 흑자로도 적자로도 읽을 수 없다")
        void refusesWhenAQuarterIsMissing() {
            List<FinancialFigures> withHole = new ArrayList<>(quarters(300, 200, 100, 50));
            withHole.set(2, FinancialFiguresFixture.from(withHole.get(2)).operatingIncome(null).build());

            assertThat(calculator.calculate(financialContext(withHole, List.of()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Altman Z-Score")
    class AltmanZ {

        private final AltmanZCalculator calculator = new AltmanZCalculator();

        @Test
        @DisplayName("다섯 항을 가중합한다 — 시가총액 항 때문에 날짜마다 값이 다르다")
        void combinesFiveTerms() {
            // 총자산 1,000 / 총부채 400 / 운전자본 200(유동 500−300) / 이익잉여금 300
            // EBIT 100(25×4) / 매출 800(200×4) / 시가총액 800
            // Z = 1.2·0.2 + 1.4·0.3 + 3.3·0.1 + 0.6·2.0 + 1.0·0.8 = 0.24+0.42+0.33+1.2+0.8 = 2.99
            List<IndicatorPoint> points = calculator.calculate(
                    balanceSheetContext(bd(800)));

            assertThat(points).hasSize(1);
            assertThat(points.getFirst().value()).isEqualByComparingTo("2.99");
        }

        @Test
        @DisplayName("총부채를 안 낸 회사도 자산 − 자기자본으로 값이 나온다")
        void derivesTotalLiabilitiesWhenTheSourceOmitsIt() {
            // 미국은 us-gaap:Liabilities가 필수 표시가 아니다. 대조군 60종목에서 그 태그로 값이
            // 잡힌 종목이 0이라 재적재로는 영영 안 채워진다 — DECK·AMZN·T·VZ·WMT가 그 무리다.
            // 같은 유도가 DebtRatioCalculator에는 이미 있어서, 한 종목에서 부채비율만 값이
            // 나가고 알트만 셋은 비었으며 그 이유가 화면에 없었다.
            List<FinancialFigures> quarters = new ArrayList<>();
            for (FinancialFigures q : balanceSheetQuarters()) {
                quarters.add(FinancialFiguresFixture.from(q).totalLiabilities(null).build());
            }

            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));
            NavigableMap<LocalDate, BigDecimal> cap = new TreeMap<>();
            cap.put(FINANCIAL_START, bd(800));

            List<IndicatorPoint> points = calculator.calculate(new IndicatorContext(
                    1L, List.of(bar), List.of(), quarters,
                    Map.of(MarketCapCalculator.CODE, cap)));

            // 자산 1,000 − 자기자본 600 = 부채 400이므로 신고했을 때와 같은 값이어야 한다.
            assertThat(points).hasSize(1);
            assertThat(points.getFirst().value()).isEqualByComparingTo("2.99");
        }

        @Test
        @DisplayName("유동항목이 없으면 값을 내지 않는다 — 금융업이 대개 여기서 빠진다")
        void yieldsNothingWithoutCurrentItems() {
            List<FinancialFigures> quarters = new ArrayList<>();
            for (FinancialFigures q : balanceSheetQuarters()) {
                quarters.add(FinancialFiguresFixture.from(q)
                        .currentAssets(null).currentLiabilities(null).build());
            }

            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));
            NavigableMap<LocalDate, BigDecimal> cap = new TreeMap<>();
            cap.put(FINANCIAL_START, bd(800));

            assertThat(calculator.calculate(new IndicatorContext(
                    1L, List.of(bar), List.of(), quarters,
                    Map.of(MarketCapCalculator.CODE, cap)))).isEmpty();
        }

        @Test
        @DisplayName("시가총액이 없는 날은 건너뛴다 — 그 항이 0이면 Z가 낮게 나와 멀쩡한 기업이 부실로 읽힌다")
        void skipsDatesWithoutMarketCap() {
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, null);

            assertThat(calculator.calculate(new IndicatorContext(
                    1L, List.of(bar), List.of(), balanceSheetQuarters(),
                    Map.of(MarketCapCalculator.CODE, new TreeMap<>())))).isEmpty();
        }

        @Test
        @DisplayName("보험·금융서비스는 계산되더라도 값을 내지 않는다 — 뜻 없는 값이 필수 조건을 통과한다")
        void refusesInsuranceAndFinancialServices() {
            // 위 combinesFiveTerms와 재무가 같다. 즉 값이 안 나오는 이유는 데이터 결손이
            // 아니라 정의역이며, 그것이 부작용과 명시 제외의 차이다.
            for (String division : List.of("KSIC:65", "KSIC:66")) {
                assertThat(calculator.calculate(inDivision(division, bd(800))))
                        .as("%s는 Altman Z의 정의역 밖이다", division)
                        .isEmpty();
            }
            // 접두가 없는 값은 제외 대상이 아니다. 이것이 SIC 65(부동산)를 KSIC 65(보험)로
            // 읽지 않는다는 것의 실동작이다(ADR-0092) — 숫자만 비교하면 여기서 안 걸린다.
            assertThat(calculator.calculate(inDivision("65", bd(800))))
                    .as("체계 접두가 없는 '65'는 어느 체계인지 알 수 없어 제외하지 않는다")
                    .hasSize(1);
        }

        @Test
        @DisplayName("KSIC 64(금융업)와 업종 미상은 제외하지 않는다 — 지주회사를 코드로 가를 수 없다")
        void keepsHoldingCompaniesAndUnknownIndustry() {
            // 64992에 금융지주와 사업지주가 함께 있어, 64를 자르면 적용 가능한 종목이 함께
            // 사라진다(ADR-0044). 업종 미상도 같은 이유로 남긴다 — 아는 64는 넣으면서
            // 모르는 것만 빼면 비일관이다.
            assertThat(calculator.calculate(inDivision("KSIC:64", bd(800)))).hasSize(1);
            assertThat(calculator.calculate(inDivision(null, bd(800)))).hasSize(1);
        }

        @Test
        @DisplayName("원시 영업이익이 없어도 유도 재료가 있으면 값이 난다 — 수정모델과 EBIT 정의를 맞춘다")
        void derivesOperatingIncomeWhenRawIsMissing() {
            // 매출 200 − 총원가·비용 175 = 25로 원시 영업이익과 같으므로 Z도 2.99 그대로다.
            // 즉 이 갈래가 여는 것은 값의 변경이 아니라 값이 없던 종목이다(ADR-0104).
            List<FinancialFigures> quarters = new ArrayList<>();
            for (FinancialFigures q : balanceSheetQuarters()) {
                quarters.add(FinancialFiguresFixture.from(q)
                        .operatingIncome(null)
                        .costsAndExpenses(bd(175))
                        .build());
            }
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));
            NavigableMap<LocalDate, BigDecimal> cap = new TreeMap<>();
            cap.put(FINANCIAL_START, bd(800));

            List<IndicatorPoint> points = calculator.calculate(new IndicatorContext(
                    1L, null, List.of(bar), List.of(), quarters, List.of(),
                    Map.of(MarketCapCalculator.CODE, cap)));

            assertThat(points).hasSize(1);
            assertThat(points.getFirst().value()).isEqualByComparingTo("2.99");
        }

        @Test
        @DisplayName("IMPORTANT — 의존 지표를 거치는 재조립 경로에서도 업종이 전달된다")
        void industrySurvivesDependencyReassembly() {
            // AltmanZ는 MARKET_CAP에 의존하므로 러너가 컨텍스트를 반드시 다시 만든다.
            // 거기서 업종이 빠지면 제외가 통째로 무효가 되는데 아무 에러도 나지 않는다.
            NavigableMap<LocalDate, BigDecimal> cap = new TreeMap<>();
            cap.put(FINANCIAL_START, bd(800));
            IndicatorContext reassembled = inDivision("KSIC:65", bd(800))
                    .withDependencies(Map.of(MarketCapCalculator.CODE, cap));

            assertThat(reassembled.industryDivisionKey()).isEqualTo("KSIC:65");
            assertThat(calculator.calculate(reassembled)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Altman Z''-Score — 수정모델")
    class AltmanZDoublePrime {

        private final AltmanZDoublePrimeCalculator calculator = new AltmanZDoublePrimeCalculator();

        @Test
        @DisplayName("네 항을 가중합한다 — 매출 항이 없고 시가총액 자리에 자기자본이 들어간다")
        void combinesFourTerms() {
            // 총자산 1,000 / 총부채 400 / 운전자본 200(유동 500−300) / 이익잉여금 300
            // EBIT 100(25×4) / 자기자본 600
            // Z'' = 6.56·0.2 + 3.26·0.3 + 6.72·0.1 + 1.05·1.5
            //     = 1.312 + 0.978 + 0.672 + 1.575 = 4.537
            List<IndicatorPoint> points = calculator.calculate(zppContext(null));

            assertThat(points).hasSize(1);
            assertThat(points.getFirst().value()).isEqualByComparingTo("4.537");
        }

        @Test
        @DisplayName("총부채를 안 낸 회사도 자산 − 자기자본으로 값이 나온다")
        void derivesTotalLiabilitiesWhenTheSourceOmitsIt() {
            // 원본 알트만과 같은 유도를 쓴다. 둘이 갈리면 같은 종목에서 한 카드만 값이 나가고
            // 그 이유가 화면에 없다 — ADR-0127이 EBIT에 대해 이미 못박은 것과 같은 자리다.
            List<FinancialFigures> quarters = new ArrayList<>();
            for (FinancialFigures q : balanceSheetQuarters()) {
                quarters.add(FinancialFiguresFixture.from(q).totalLiabilities(null).build());
            }
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));

            List<IndicatorPoint> points = calculator.calculate(new IndicatorContext(
                    1L, null, List.of(bar), List.of(), quarters, List.of(), Map.of()));

            assertThat(points).hasSize(1);
            assertThat(points.getFirst().value()).isEqualByComparingTo("4.537");
        }

        @Test
        @DisplayName("IMPORTANT — 매출도 시가총액도 없이 값이 난다. 원본과 갈리는 지점이 여기다")
        void needsNeitherRevenueNorMarketCap() {
            List<FinancialFigures> quarters = new ArrayList<>();
            for (FinancialFigures q : balanceSheetQuarters()) {
                quarters.add(FinancialFiguresFixture.from(q).revenue(null).build());
            }
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));
            // 의존 지표를 아예 안 넘긴다 — MARKET_CAP을 조회하면 여기서 터진다.
            IndicatorContext noCap = new IndicatorContext(
                    1L, null, List.of(bar), List.of(), quarters, List.of(), Map.of());

            assertThat(calculator.calculate(noCap)).hasSize(1);
            assertThat(new AltmanZCalculator().dataRequirement().dependsOn())
                    .as("원본은 시가총액에 의존한다 — 그 차이가 이 테스트의 전제다")
                    .contains(MarketCapCalculator.CODE);
            assertThat(calculator.dataRequirement().dependsOn()).isEmpty();
        }

        @Test
        @DisplayName("분기가 넷에 못 미치면 값을 내지 않는다 — TTM이 성립하지 않는다")
        void yieldsNothingWithoutFourQuarters() {
            List<FinancialFigures> three = balanceSheetQuarters().subList(0, 3);
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));

            assertThat(calculator.calculate(new IndicatorContext(
                    1L, null, List.of(bar), List.of(), three, List.of(), Map.of()))).isEmpty();
        }

        @Test
        @DisplayName("자기자본이 없으면 값을 내지 않는다 — 0으로 두면 X4가 사라져 낮게 나온다")
        void yieldsNothingWithoutEquity() {
            List<FinancialFigures> quarters = new ArrayList<>();
            for (FinancialFigures q : balanceSheetQuarters()) {
                quarters.add(FinancialFiguresFixture.from(q).totalEquity(null).build());
            }
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));

            assertThat(calculator.calculate(new IndicatorContext(
                    1L, null, List.of(bar), List.of(), quarters, List.of(), Map.of()))).isEmpty();
        }

        @Test
        @DisplayName("총자산·총부채가 0 이하면 값을 내지 않는다 — 분모가 성립하지 않는다")
        void yieldsNothingWithUnusableDenominators() {
            for (String field : List.of("assets", "liabilities")) {
                List<FinancialFigures> quarters = new ArrayList<>();
                for (FinancialFigures q : balanceSheetQuarters()) {
                    FinancialFiguresFixture f = FinancialFiguresFixture.from(q);
                    quarters.add(("assets".equals(field)
                            ? f.totalAssets(BigDecimal.ZERO)
                            : f.totalLiabilities(BigDecimal.ZERO)).build());
                }
                PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));

                assertThat(calculator.calculate(new IndicatorContext(
                        1L, null, List.of(bar), List.of(), quarters, List.of(), Map.of())))
                        .as("%s가 0이면 분모가 없다", field)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("업종 정의역이 원본과 같다 — 판정을 복사하지 않고 원본의 것을 부른다")
        void sharesTheDomainWithTheOriginal() {
            for (String division : List.of("KSIC:65", "KSIC:66", "SIC:60", "SIC:63", "SIC:64")) {
                assertThat(calculator.calculate(zppContext(division)))
                        .as("%s는 Altman 계열의 정의역 밖이다", division)
                        .isEmpty();
            }
            // 값이 나는 쪽이 사업회사인 중분류는 걷었다(2026-10-31 · 부동산 서비스 · 로열티·특허 · ADR-0092).
            assertThat(calculator.calculate(zppContext("SIC:65"))).hasSize(1);
            assertThat(calculator.calculate(zppContext("SIC:67"))).hasSize(1);
            // 접두가 없으면 어느 체계인지 알 수 없어 제외하지 않는다(ADR-0092).
            assertThat(calculator.calculate(zppContext("65"))).hasSize(1);
            // 지주회사 버킷과 업종 미상은 남긴다(ADR-0044).
            assertThat(calculator.calculate(zppContext("KSIC:64"))).hasSize(1);
            assertThat(calculator.calculate(zppContext(null))).hasSize(1);
        }

        @Test
        @DisplayName("거래일 전체에 같은 값이 펼쳐지고 스케일이 저장 컬럼과 맞는다")
        void spreadsOneValueOverEveryBar() {
            List<PriceBar> bars = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                bars.add(new PriceBar(FINANCIAL_START.plusDays(i),
                        bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100)));
            }
            List<IndicatorPoint> points = calculator.calculate(new IndicatorContext(
                    1L, null, bars, List.of(), balanceSheetQuarters(), List.of(), Map.of()));

            assertThat(points).hasSize(3);
            assertThat(points).allSatisfy(point -> {
                assertThat(point.value()).isEqualByComparingTo("4.537");
                // 저장 컬럼이 numeric(24,8)이라 메모리 값도 거기 맞춰 접혀야 두 경로가 안 갈린다.
                assertThat(point.value().scale()).isEqualTo(8);
            });
        }

        @Test
        @DisplayName("원시 영업이익이 없어도 유도 재료가 있으면 값이 난다")
        void derivesOperatingIncomeWhenRawIsMissing() {
            // 매출 200 − 총원가·비용 175 = 25. 원시 영업이익과 같은 값이므로 Z''도 같아야 한다.
            List<FinancialFigures> quarters = new ArrayList<>();
            for (FinancialFigures q : balanceSheetQuarters()) {
                quarters.add(FinancialFiguresFixture.from(q)
                        .operatingIncome(null)
                        .revenue(bd(200))
                        .costsAndExpenses(bd(175))
                        .build());
            }
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));

            List<IndicatorPoint> points = calculator.calculate(new IndicatorContext(
                    1L, null, List.of(bar), List.of(), quarters, List.of(), Map.of()));

            assertThat(points).hasSize(1);
            assertThat(points.getFirst().value()).isEqualByComparingTo("4.537");
        }

        /** {@link #balanceSheetQuarters}와 재무가 같고 업종만 다르다. 시가총액은 넘기지 않는다. */
        private IndicatorContext zppContext(String industryDivisionKey) {
            PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null, bd(100));
            return new IndicatorContext(1L, industryDivisionKey, List.of(bar), List.of(),
                    balanceSheetQuarters(), List.of(), Map.of());
        }
    }

    @Nested
    @DisplayName("거래일 수 — 세는 것이 상장 경과가 아니라 저장된 봉이다")
    class TradingDays {

        private final TradingDays750Calculator calculator = new TradingDays750Calculator();

        @Test
        @DisplayName("IMPORTANT — 봉이 751개여도 마지막 값은 750이다. min(i+1, 750)이라 창이 넓어져도 불변이다")
        void saturatesAtThreshold() {
            List<IndicatorPoint> points = calculator.calculate(contextOf(flat(751, 1_000)));

            assertThat(points).hasSize(751);
            assertThat(points.getLast().value()).isEqualByComparingTo("750");
        }

        @Test
        @DisplayName("이력이 모자라면 그 수를 그대로 낸다 — 값을 내지 않으면 왜 빠졌는지가 판정불가로 뭉개진다")
        void reportsShortHistoryAsIs() {
            List<IndicatorPoint> points = calculator.calculate(contextOf(flat(749, 1_000)));

            assertThat(points).hasSize(749);
            assertThat(points.getLast().value()).isEqualByComparingTo("749");
        }

        @Test
        @DisplayName("봉이 둘뿐인 종목도 2를 낸다 — 수집 결손이 화면에 숫자로 드러나는 유일한 경로다")
        void countsEvenTwoBars() {
            List<IndicatorPoint> points = calculator.calculate(contextOf(flat(2, 1_000)));

            assertThat(points.getLast().value()).isEqualByComparingTo("2");
            assertThat(points.get(0).value()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("창 안의 산출 일자마다 값을 낸다 — 마지막 봉 하나만 내면 과거 일자가 통째로 빈다")
        void writesEveryBarInTheWindow() {
            List<PriceBar> bars = flat(300, 1_000);

            List<IndicatorPoint> points = calculator.calculate(contextOf(bars));

            assertThat(points).hasSize(300);
            assertThat(points.get(0).tradeDate()).isEqualTo(bars.get(0).tradeDate());
            assertThat(points.get(0).value()).isEqualByComparingTo("1");
            assertThat(points.getLast().tradeDate()).isEqualTo(bars.getLast().tradeDate());
            assertThat(points.getLast().value()).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("포화 경계가 정확히 750번째 봉이다 — 게이트가 >= 750이라 한 칸이 판정을 가른다")
        void saturationBoundarySitsOnTheSevenHundredFiftiethBar() {
            List<IndicatorPoint> points = calculator.calculate(contextOf(flat(800, 1_000)));

            assertThat(points.get(748).value()).isEqualByComparingTo("749");
            assertThat(points.get(749).value()).isEqualByComparingTo("750");
            assertThat(points.get(750).value()).isEqualByComparingTo("750");
        }

        @Test
        @DisplayName("봉이 없으면 값을 내지 않는다")
        void skipsWhenNoBars() {
            assertThat(calculator.calculate(contextOf(List.of()))).isEmpty();
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    /** 영업이익만 다른 분기 넷(최신 우선). 나머지 항목은 비어 있다. */
    private static List<FinancialFigures> quarters(int... operatingIncomes) {
        List<FinancialFigures> quarters = new ArrayList<>(operatingIncomes.length);
        LocalDate periodEnd = LocalDate.of(2025, 12, 31);
        for (int income : operatingIncomes) {
            quarters.add(FinancialFiguresFixture.quarter(periodEnd)
                    .operatingIncome(bd(income))
                    .build());
            periodEnd = periodEnd.minusMonths(3);
        }
        return quarters;
    }

    /** 두 Altman 계열이 읽는 항을 모두 채운 분기 넷. 저량은 매 분기 같은 잔액이다. */
    private static List<FinancialFigures> balanceSheetQuarters() {
        List<FinancialFigures> quarters = new ArrayList<>(4);
        LocalDate periodEnd = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < 4; i++) {
            quarters.add(FinancialFiguresFixture.quarter(periodEnd)
                    .totalAssets(bd(1_000))
                    .totalLiabilities(bd(400))
                    .totalEquity(bd(600))      // 자산 1,000 − 부채 400. Z''가 읽는다
                    .currentAssets(bd(500))
                    .currentLiabilities(bd(300))
                    .retainedEarnings(bd(300))
                    .operatingIncome(bd(25))   // TTM 100
                    .revenue(bd(200))          // TTM 800
                    .build());
            periodEnd = periodEnd.minusMonths(3);
        }
        return quarters;
    }

    private static IndicatorContext balanceSheetContext(BigDecimal marketCap) {
        return inDivision(null, marketCap);
    }

    /** {@link #balanceSheetContext}와 재무가 같고 업종만 다르다. 값 차이의 원인을 업종으로 못박는다. */
    private static IndicatorContext inDivision(String industryDivisionKey, BigDecimal marketCap) {
        PriceBar bar = new PriceBar(FINANCIAL_START, bd(8), bd(8), bd(8), bd(8), bd(8), 10L, null,
                bd(100));
        NavigableMap<LocalDate, BigDecimal> cap = new TreeMap<>();
        cap.put(FINANCIAL_START, marketCap);
        return new IndicatorContext(1L, industryDivisionKey, List.of(bar), List.of(),
                balanceSheetQuarters(), List.of(), Map.of(MarketCapCalculator.CODE, cap));
    }

    private static IndicatorContext financialContext(List<FinancialFigures> quarterly,
                                                     List<FinancialFigures> annual) {
        PriceBar bar = new PriceBar(FINANCIAL_START, bd(100), bd(100), bd(100), bd(100), bd(100),
                10L, null, null);
        return new IndicatorContext(1L, List.of(bar), annual, quarterly, Map.of());
    }


    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }

    private static IndicatorContext contextOf(List<PriceBar> bars) {
        return new IndicatorContext(1L, bars, List.of(), List.of(), Map.of());
    }

    /** SMA200 의존만 채운 컨텍스트. 시세는 이 계산기들이 쓰지 않는다. */
    private static IndicatorContext withSma(NavigableMap<LocalDate, BigDecimal> sma) {
        return new IndicatorContext(1L, List.of(), List.of(), List.of(),
                Map.of(Sma200Calculator.CODE, sma));
    }

    /** 매일 {@code ratio}배씩 곱해지는 이동평균. ratio가 1이면 완전한 횡보다. */
    private static NavigableMap<LocalDate, BigDecimal> risingSma(double base, double ratio) {
        NavigableMap<LocalDate, BigDecimal> sma = new TreeMap<>();
        double value = base;
        for (int i = 0; i < Sma200Slope60Calculator.WINDOW; i++) {
            sma.put(START.plusDays(i), BigDecimal.valueOf(value));
            value *= ratio;
        }
        return sma;
    }

    private static List<PriceBar> flat(int count, int price) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(bar(START.plusDays(i), price, price, price));
        }
        return bars;
    }

    private static List<PriceBar> withTradeValue(int count, long tradeValue) {
        List<PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(new PriceBar(START.plusDays(i), bd(1_000), bd(1_000), bd(1_000),
                    bd(1_000), bd(1_000), 10L, BigDecimal.valueOf(tradeValue), null));
        }
        return bars;
    }

    /** 수정종가 = 종가(분할 없음). 고가·저가만 따로 준다. */
    private static PriceBar bar(LocalDate date, int close, int high, int low) {
        return new PriceBar(date, bd(close), bd(high), bd(low), bd(close), bd(close), 10L, null, null);
    }
}
