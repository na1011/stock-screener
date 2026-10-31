package com.stockscreener.indicator.calculator;

import com.stockscreener.indicator.FinancialFigures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PBR · NCAV의 보통주 몫 기저(탐색 {@code PERSHARE-DEN-PLAN-1026.md} v1.1 5절). 세 상태 — 이 규칙으로 안 읽음(지금 경로) · 못 정함(미표시) · 정함 —
 * 를 가르지 않으면 관문을 켰을 때 국내 · 클래스 축 줄 없는 미국 종목의 PBR까지 빈다.
 */
class CommonEquityBasisTest {

    private static final LocalDate END = LocalDate.of(2025, 12, 31);

    private static FinancialFiguresFixture bank() {
        return FinancialFiguresFixture.annual(END).totalEquity(new BigDecimal("213000")).totalEquityControlling(new BigDecimal("212291"));
    }

    @Test
    @DisplayName("이 규칙으로 안 읽은 행(관문 꺼짐 · 국내)은 지배 자본 그대로다")
    void notReadKeepsTheCurrentPath() {
        assertThat(FinancialMath.commonEquity(bank().build())).isEqualByComparingTo("212291");
    }

    @Test
    @DisplayName("국내 관문 켬 행(보통주 몫 줄은 있고 자본 열은 안 읽음)은 지배 자본 그대로다 — 못 정함으로 비우지 않는다")
    void krCommonRowsKeepTheCurrentPath() {
        FinancialFigures f = bank().krCommon(new BigDecimal("100"), "T", "kr-ni", new BigDecimal("100")).build();

        assertThat(FinancialMath.commonEquity(f)).isEqualByComparingTo("212291");
        assertThat(FinancialMath.claimsAheadOfCommon(bank().totalLiabilities(new BigDecimal("50"))
                .krCommon(new BigDecimal("100"), "T", "kr-ni", new BigDecimal("100")).build())).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("정함 — 지배 자본 − 우선주 장부가(C 212,291 − 20,050)")
    void decidedSubtractsTheNonCommonClaim() {
        FinancialFigures f = bank().commonEquity(new BigDecimal("20050"), new BigDecimal("20050"), "stmt").build();

        assertThat(FinancialMath.commonEquity(f)).isEqualByComparingTo("192241");
    }

    @Test
    @DisplayName("국내 정함 — (지배 자본 − 신종자본증권) × 보통주 몫 · 뺄 것 0 · 몫 1이면 지금 경로")
    void domesticSubtractsHybridThenTakesTheCommonShare() {
        FinancialFigures f = bank().krEquity(new BigDecimal("12291"), new BigDecimal("0.9"), "kr").build();
        assertThat(FinancialMath.commonEquity(f)).isEqualByComparingTo("180000");

        FinancialFigures none = bank().krEquity(BigDecimal.ZERO, BigDecimal.ONE, "kr").build();
        assertThat(FinancialMath.commonEquity(none)).isEqualByComparingTo("212291");
    }

    @Test
    @DisplayName("국내 kr-s(총자본이 곧 지배 자본)는 지배 자본 칸이 비어도 총자본에서 뺀다 · kr이면 미표시(비지배를 섞는다)")
    void domesticSeparateEntityUsesTotalEquity() {
        FinancialFiguresFixture separate = FinancialFiguresFixture.annual(END).totalEquity(new BigDecimal("1000"));
        assertThat(FinancialMath.commonEquity(separate.krEquity(new BigDecimal("100"), new BigDecimal("0.5"), "kr-s").build()))
                .isEqualByComparingTo("450");
        FinancialFiguresFixture consolidated = FinancialFiguresFixture.annual(END).totalEquity(new BigDecimal("1000"));
        assertThat(FinancialMath.commonEquity(consolidated.krEquity(new BigDecimal("100"), new BigDecimal("0.5"), "kr").build()))
                .isNull();
    }

    @Test
    @DisplayName("국내 NCAV 청구권 — 총부채 + 신종자본증권 · 메자닌 간극은 안 붙인다(미국 개념) · 보통주 몫은 계산기가 곱한다")
    void domesticNcavClaimsSkipTheMezzanineGap() {
        FinancialFigures f = FinancialFiguresFixture.annual(END).totalAssets(new BigDecimal("5120")).totalLiabilities(new BigDecimal("2178"))
                .totalEquity(new BigDecimal("1181")).currentAssets(new BigDecimal("1811"))
                .krEquity(new BigDecimal("100"), new BigDecimal("0.8"), "kr").build();
        assertThat(FinancialMath.claimsAheadOfCommon(f)).isEqualByComparingTo("2278");
        assertThat(FinancialMath.commonFraction(f)).isEqualByComparingTo("0.8");
    }

    @Test
    @DisplayName("손익 칸 기저 대체일(재고 A6) — 그날부터의 봉은 그 칸을 모른다 · 잔액과 다른 칸은 그대로")
    void supersededFlowIsUnknownFromThatDay() {
        FinancialFigures f = FinancialFiguresFixture.annual(END).revenue(new BigDecimal("100")).netIncome(new BigDecimal("10"))
                .totalEquity(new BigDecimal("500")).flowsSupersededOn(java.util.Map.of("revenue", LocalDate.of(2026, 5, 15))).build();
        assertThat(f.asSeenOn(LocalDate.of(2026, 5, 14)).revenue()).isEqualByComparingTo("100");
        FinancialFigures after = f.asSeenOn(LocalDate.of(2026, 5, 15));
        assertThat(after.revenue()).isNull();
        assertThat(after.netIncome()).isEqualByComparingTo("10");
        assertThat(after.totalEquity()).isEqualByComparingTo("500");

        FinancialFigures controlling = FinancialFiguresFixture.annual(END).netIncome(new BigDecimal("10"))
                .netIncomeControlling(new BigDecimal("8"))
                .flowsSupersededOn(java.util.Map.of("net_income_controlling", LocalDate.of(2026, 5, 15))).build()
                .asSeenOn(LocalDate.of(2026, 6, 1));
        assertThat(controlling.netIncomeControlling()).isNull();
        assertThat(controlling.netIncome()).as("한쪽만 비우면 연결 폴백이 기저를 바꾼다").isNull();

        // 국내 관문 켬 행 — 주당 지표 분자는 보통주 몫 엔진의 값이다. 손익 칸이 낡으면 그 몫도 함께 모른다(자본 재료는 그대로).
        FinancialFigures kr = FinancialFiguresFixture.annual(END).netIncome(new BigDecimal("10")).netIncomeControlling(new BigDecimal("8"))
                .krCommon(new BigDecimal("8"), "T", "kr-ni", new BigDecimal("8"))
                .flowsSupersededOn(java.util.Map.of("net_income", LocalDate.of(2026, 5, 15))).build()
                .asSeenOn(LocalDate.of(2026, 6, 1));
        assertThat(kr.commonContinuingIncome()).isNull();
        assertThat(kr.common().payoutBase()).isNull();
    }

    @Test
    @DisplayName("4분기로 끝나는 창의 네 분기 합은 그 해 FY다 — 분기 칸이 비면(기저 대체) FY 값을 쓴다 · FY가 안 달렸으면 그대로 빈다")
    void fourthQuarterWindowFallsBackToTheFiscalYear() {
        FinancialFigures fy = FinancialFiguresFixture.annual(LocalDate.of(2025, 12, 31)).revenue(new BigDecimal("1000")).build();
        java.util.List<FinancialFigures> quarters = new java.util.ArrayList<>(java.util.List.of(
                FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31)).revenue(new BigDecimal("260")).build().withAnnualOfYear(fy),
                FinancialFiguresFixture.quarter(LocalDate.of(2025, 9, 30)).revenue(new BigDecimal("250")).build(),
                FinancialFiguresFixture.quarter(LocalDate.of(2025, 6, 30)).revenue(null).build(),
                FinancialFiguresFixture.quarter(LocalDate.of(2025, 3, 31)).revenue(null).build()));
        assertThat(FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::revenue)).isEqualByComparingTo("1000");
        quarters.set(0, FinancialFiguresFixture.quarter(LocalDate.of(2025, 12, 31)).revenue(new BigDecimal("260")).build());
        assertThat(FinancialMath.sumTrailingFourQuarters(quarters, FinancialFigures::revenue)).isNull();
    }

    @Test
    @DisplayName("못 정함은 미표시다 — 지배 자본으로 메우지 않는다")
    void undecidedIsNotShown() {
        FinancialFigures f = bank().commonEquity(null, null, "open").build();

        assertThat(FinancialMath.commonEquity(f)).isNull();
    }

    @Test
    @DisplayName("뺄 것이 있는데 지배 자본이 없으면 미표시 — 총자본 − 우선주는 비지배를 섞는다(NRG 꼴)")
    void nonZeroClaimWithoutControllingEquityIsNotShown() {
        FinancialFigures f = FinancialFiguresFixture.annual(END).totalEquity(new BigDecimal("1681"))
                .commonEquity(new BigDecimal("650"), new BigDecimal("650"), "tag").build();

        assertThat(FinancialMath.commonEquity(f)).isNull();
    }

    @Test
    @DisplayName("뺄 것 0이면 지금 경로 그대로(지배 ?: 총자본) — 이 규칙이 비지배 축을 안 바꾼다")
    void zeroClaimKeepsTheCurrentBasis() {
        FinancialFigures f = FinancialFiguresFixture.annual(END).totalEquity(new BigDecimal("1681"))
                .commonEquity(BigDecimal.ZERO, BigDecimal.ZERO, "none").build();

        assertThat(FinancialMath.commonEquity(f)).isEqualByComparingTo("1681");
    }

    @Test
    @DisplayName("NCAV — 총부채 + 영구자본 우선주 + 메자닌(자산 − 부채 − 자본 간극 · CELH 1,760)")
    void ncavClaimsIncludePreferredAndMezzanine() {
        FinancialFigures f = FinancialFiguresFixture.annual(END).totalAssets(new BigDecimal("5120")).totalLiabilities(new BigDecimal("2178"))
                .totalEquity(new BigDecimal("1181")).currentAssets(new BigDecimal("1811"))
                .commonEquity(BigDecimal.ZERO, BigDecimal.ZERO, "mezz").build();

        assertThat(FinancialMath.claimsAheadOfCommon(f)).isEqualByComparingTo("3939");
    }

    @Test
    @DisplayName("NCAV — 총부채가 「자산 − 자본」 폴백이면 메자닌이 이미 들어 있다 · 우선주는 더한다")
    void ncavFallbackLiabilitiesAlreadyHoldTheMezzanine() {
        FinancialFigures f = FinancialFiguresFixture.annual(END).totalAssets(new BigDecimal("5120")).totalEquity(new BigDecimal("1181"))
                .commonEquity(new BigDecimal("100"), new BigDecimal("100"), "stmt").build();

        assertThat(FinancialMath.claimsAheadOfCommon(f)).isEqualByComparingTo("4039");
    }

    @Test
    @DisplayName("NCAV — 이 규칙으로 안 읽은 행은 총부채 그대로 · 못 정함은 미표시")
    void ncavStates() {
        FinancialFigures plain = FinancialFiguresFixture.annual(END).totalLiabilities(new BigDecimal("2178")).build();
        FinancialFigures open = FinancialFiguresFixture.annual(END).totalLiabilities(new BigDecimal("2178"))
                .commonEquity(null, null, "open").build();

        assertThat(FinancialMath.claimsAheadOfCommon(plain)).isEqualByComparingTo("2178");
        assertThat(FinancialMath.claimsAheadOfCommon(open)).isNull();
    }
}
