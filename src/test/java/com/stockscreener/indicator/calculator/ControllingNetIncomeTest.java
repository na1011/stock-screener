package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.IndicatorPoint;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주당 지표의 분자가 <b>지배기업 소유주 귀속</b> 순이익인지, 그리고 그 폴백이
 * <b>창 단위</b>인지 고정한다.
 *
 * <p>이 테스트가 없으면 무너지는 방식이 조용하다 — 연결 총액을 써도 값이 나오고, 네 분기 중
 * 일부만 지배주주 값을 가질 때 섞어 더해도 값이 나온다. <b>둘 다 그럴듯한 숫자</b>라 화면에는
 * 흔적이 없다.
 *
 * <p>실제 크기는 작지 않다 — 배당표 전수 대조 512쌍에서 96쌍이 5% 초과, 65쌍이 20% 초과이고
 * 지주회사에서 최대 5.48배다(다우데이타).
 */
class ControllingNetIncomeTest {

    private final PerCalculator per = new PerCalculator();
    private final EpsTtmCalculator epsTtm = new EpsTtmCalculator();

    @Test
    @DisplayName("네 분기 모두 지배주주 값이 있으면 그것으로 계산한다 — 연결 총액이 아니다")
    void usesControllingWhenAllQuartersHaveIt() {
        // 연결 총액은 분기당 250(TTM 1000), 지배주주는 분기당 200(TTM 800).
        // 주식수 100 → 지배주주 EPS 8. 주가 150 → PER 18.75.
        // 연결 총액을 쓰면 EPS 10 · PER 15가 되는데 그 값도 그럴듯하다.
        IndicatorContext context = context(quarters(4, "250", "200"), "150");

        assertThat(per.calculate(context).get(0).value()).isEqualByComparingTo("18.75");
        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("지배주주 값이 한 분기도 없으면 연결 총액으로 폴백한다 — 비지배지분이 없는 회사다")
    void fallsBackWhenNoQuarterHasControlling() {
        // 소스가 그 행을 아예 내지 않는 것이 정상인 경우다(실측: 삼성바이오로직스의
        // 비지배지분이 정확히 0). 그때는 연결 총액이 곧 지배주주 귀속분이다.
        IndicatorContext context = context(quarters(4, "250", null), "150");

        assertThat(per.calculate(context).get(0).value()).isEqualByComparingTo("15");
        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("한 분기라도 지배주주 값이 비면 네 분기 다 연결 총액으로 간다 — 섞어 더하지 않는다")
    void doesNotMixTwoBases() {
        // 이것이 창 단위 폴백의 이유다. 행마다 폴백하면 200+200+200+250 = 850이 되어
        // 지배주주(800)도 연결 총액(1000)도 아닌 수가 나오고, EPS 8.5는 그럴듯하다.
        List<FinancialFigures> quarters = quarters(4, "250", "200");
        quarters.set(3, FinancialFiguresFixture.from(quarters.get(3))
                .netIncomeControlling(null).build());
        IndicatorContext context = context(quarters, "150");

        // 850이 아니라 1000으로 간다 → EPS 10 · PER 15.
        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("10");
        assertThat(per.calculate(context).get(0).value()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("지배주주 이익이 적자면 PER은 값을 내지 않고 EPS는 음수를 낸다")
    void controllingLossSplitsTheTwoIndicators() {
        // 연결 총액은 흑자인데 지배주주는 적자인 경우다. 지주회사에서 실제로 생긴다.
        // PER이 연결 총액을 봤다면 값을 냈을 것이고, 그것은 「적자 기업이 저PER 최상위」다.
        IndicatorContext context = context(quarters(4, "250", "-50"), "150");

        assertThat(per.calculate(context)).isEmpty();
        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("-2");
    }

    private static IndicatorContext context(List<FinancialFigures> quarters, String close) {
        return new IndicatorContext(1L, List.of(priceBar(new BigDecimal(close))), List.of(),
                quarters, null);
    }

    private static PriceBar priceBar(BigDecimal close) {
        return new PriceBar(LocalDate.of(2026, 3, 2), close, close, close, close, close,
                1000L, null, null);
    }

    /** @param controlling null이면 소스가 그 행을 내지 않은 것이다 */
    private static List<FinancialFigures> quarters(int count, String netIncome,
                                                   String controlling) {
        List<FinancialFigures> list = new ArrayList<>(count);
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < count; i++) {
            list.add(FinancialFiguresFixture.quarter(end.minusMonths(3L * i))
                    .netIncome(new BigDecimal(netIncome))
                    .netIncomeControlling(controlling == null ? null : new BigDecimal(controlling))
                    .sharesOutstanding(BigDecimal.valueOf(100))
                    .build());
        }
        return list;
    }
}
