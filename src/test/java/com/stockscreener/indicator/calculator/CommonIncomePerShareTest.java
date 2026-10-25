package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;
import com.stockscreener.indicator.IndicatorContext;
import com.stockscreener.indicator.PriceBar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주당 지표의 분자가 <b>보통주 귀속</b>인지 고정한다(미국 관문 켬 · 탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1).
 *
 * <p>무너지는 방식이 조용하다 — 지배주주 순이익에는 우선주 배당 · 참가증권 · GP 몫이 들어 있어 보통주 수로 나누면 EPS가 그만큼 부풀고
 * PER은 그럴듯하게 낮다(TPG 33% · MCHP 48% · 은행 5~9%). HPE · APG는 보통주 몫이 적자인데 PER이 나왔다.
 */
class CommonIncomePerShareTest {

    private final PerCalculator per = new PerCalculator();
    private final EpsTtmCalculator epsTtm = new EpsTtmCalculator();

    @Test
    @DisplayName("이 규칙으로 안 읽은 행(관문 꺼짐 · 국내)은 지금과 같다 — 지배 NI")
    void unreadRowsBehaveAsBefore() {
        // 지배 200 × 4 = 800 · 주식수 100 → EPS 8.
        assertThat(epsTtm.calculate(context(quarters(null, null, null))).get(0).value()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("보통주 몫으로 낸다 — 우선주 배당 · 참가증권 배분을 뺀 몫")
    void perShareUsesTheCommonShare() {
        // 보통주 몫 150 × 4 = 600 → EPS 6 · PER 150/6 = 25.
        IndicatorContext context = context(quarters("150", "T", "decl"));
        assertThat(epsTtm.calculate(context).get(0).value()).isEqualByComparingTo("6");
        assertThat(per.calculate(context).get(0).value()).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("배분 0의 증인(ni)이면 지배 NI 그대로다")
    void noAllocationKeepsControlling() {
        assertThat(epsTtm.calculate(context(quarters(null, "T", "ni"))).get(0).value()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("보통주 몫이 적자면 PER을 안 낸다 — 지배 NI가 흑자여도(HPE · APG)")
    void negativeCommonShareHidesPer() {
        assertThat(per.calculate(context(quarters("-10", "T", "decl")))).isEmpty();
    }

    @Test
    @DisplayName("한 분기라도 못 정하면 그 창은 값이 없다 — 지배 NI로 안 내려간다")
    void undecidedQuarterHidesTheWindow() {
        List<FinancialFigures> quarters = quarters("150", "T", "decl");
        quarters.set(2, FinancialFiguresFixture.from(quarters.get(2)).common(null, "T", "none").build());
        IndicatorContext context = context(quarters);
        assertThat(epsTtm.calculate(context)).isEmpty();
        assertThat(per.calculate(context)).isEmpty();
    }

    @Test
    @DisplayName("보통주 몫에서도 지배 중단영업을 뺀다 — F2b 갈래 그대로")
    void subtractsControllingDiscontinuedFromTheCommonShare() {
        // 최신 분기 지배 중단 100 → (150 × 4) − 100 = 500 → EPS 5.
        List<FinancialFigures> quarters = quarters("150", "T", "decl");
        for (int i = 0; i < 4; i++) {
            quarters.set(i, FinancialFiguresFixture.from(quarters.get(i))
                    .discontinued(i == 0 ? new BigDecimal("120") : BigDecimal.ZERO, i == 0 ? new BigDecimal("100") : null).build());
        }
        assertThat(epsTtm.calculate(context(quarters)).get(0).value()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("계속영업 EPS에서 지은 몫(C)은 중단을 다시 안 뺀다")
    void continuingBasisIsTakenAsIs() {
        List<FinancialFigures> quarters = quarters("150", "C", "eps");
        quarters.set(0, FinancialFiguresFixture.from(quarters.get(0))
                .discontinued(new BigDecimal("120"), new BigDecimal("100")).build());
        assertThat(epsTtm.calculate(context(quarters)).get(0).value()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("연간 보통주 순이익(중단 포함)이 배당성향 분모 — 이 규칙으로 안 읽은 행은 지배 NI")
    void annualCommonNetIncome() {
        FinancialFigures read = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31))
                .netIncomeControlling(new BigDecimal("800")).common(new BigDecimal("600"), "T", "decl").build();
        FinancialFigures unread = FinancialFiguresFixture.from(read).common(null, null, null).build();
        assertThat(FinancialMath.commonNetIncomeOf(read)).isEqualByComparingTo("600");
        assertThat(FinancialMath.commonNetIncomeOf(
                FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31)).netIncomeControlling(new BigDecimal("800")).build()))
                .isEqualByComparingTo("800");
        assertThat(FinancialMath.commonNetIncomeOf(unread)).as("유도가 안 돈 행(관문 켬) — 값 없음").isNull();
    }

    @Test
    @DisplayName("국내 켬 — 배분 0(kr-ni)도 값을 싣고, 지배 NI가 빈 행에서도 판정이 정한 값으로 낸다(창 단위 연결 폴백을 안 탄다)")
    void krNoAllocationCarriesItsValue() {
        // 지배 NI가 빈 별도 기저 행 — 판정이 행 NI(별도 = 지배)를 값으로 박았다 · 250 × 4 = 1,000 → EPS 10.
        List<FinancialFigures> quarters = new ArrayList<>();
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < 4; i++) {
            quarters.add(FinancialFiguresFixture.quarter(end.minusMonths(3L * i)).netIncome(new BigDecimal("250"))
                    .netIncomeControlling(null).sharesOutstanding(BigDecimal.valueOf(100))
                    .krCommon(new BigDecimal("250"), "T", "kr-ni", new BigDecimal("250")).build());
        }
        assertThat(epsTtm.calculate(context(quarters)).get(0).value()).isEqualByComparingTo("10");

        // 한 분기라도 붙은 것이 없으면(스냅숏이 갈렸거나 유도 전) 그 창은 미표시 — 연결 NI로 안 내려간다.
        quarters.set(1, FinancialFiguresFixture.from(quarters.get(1)).krCommon(null, null, null, null).build());
        assertThat(epsTtm.calculate(context(quarters))).isEmpty();
    }

    @Test
    @DisplayName("국내 켬 — 배당성향 분모는 판정이 정한 값(「지배 NI − 신종자본증권 몫」) · 못 정했으면 미표시 · 미국은 보통주 몫 그대로")
    void krPayoutBaseIsTheJudgedDenominator() {
        FinancialFigures kr = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31)).netIncomeControlling(new BigDecimal("800"))
                .krCommon(new BigDecimal("600"), "T", "exw", new BigDecimal("700")).build();
        assertThat(FinancialMath.commonNetIncomeOf(kr)).isEqualByComparingTo("700");

        FinancialFigures undecided = FinancialFiguresFixture.from(kr).krCommon(new BigDecimal("600"), "T", "exw", null).build();
        assertThat(FinancialMath.commonNetIncomeOf(undecided)).isNull();

        FinancialFigures us = FinancialFiguresFixture.from(kr).common(new BigDecimal("600"), "T", "decl").build();
        assertThat(FinancialMath.commonNetIncomeOf(us)).isEqualByComparingTo("600");
    }

    private static IndicatorContext context(List<FinancialFigures> quarters) {
        BigDecimal close = new BigDecimal("150");
        return new IndicatorContext(1L, List.of(new PriceBar(LocalDate.of(2026, 3, 2), close, close, close, close, close, 1000L,
                null, null)), List.of(), quarters, null);
    }

    /** 연결 250 · 지배 200 · 주식수 100인 네 분기. {@code basis}가 null이면 이 규칙으로 안 읽은 행. */
    private static List<FinancialFigures> quarters(String common, String basis, String branch) {
        List<FinancialFigures> list = new ArrayList<>(4);
        LocalDate end = LocalDate.of(2025, 12, 31);
        for (int i = 0; i < 4; i++) {
            FinancialFiguresFixture f = FinancialFiguresFixture.quarter(end.minusMonths(3L * i))
                    .netIncome(new BigDecimal("250"))
                    .netIncomeControlling(new BigDecimal("200"))
                    .sharesOutstanding(BigDecimal.valueOf(100));
            if (basis != null) {
                f = f.common(common == null ? null : new BigDecimal(common), basis, branch);
            }
            list.add(f.build());
        }
        return list;
    }
}
