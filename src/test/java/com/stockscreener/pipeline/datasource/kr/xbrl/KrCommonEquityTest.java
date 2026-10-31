package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Member;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Source;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonEquity.Equity;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonEquity.Issuer;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.ShareRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 국내 보통주 자본 재료(ADR-0199) — 신종자본증권은 지배기업 자신의 재무제표가 말하고, 우선주 몫은 원장이 참가 지분으로 실을 때만 나눈다. */
class KrCommonEquityTest {

    private static final LocalDate D = LocalDate.of(2026, 6, 30);
    private static final LocalDate FY = LocalDate.of(2025, 12, 31);
    private static final Member HYBRID_COMPONENT = new Member("ComponentsOfEquityAxis", "HybridBondsMember", Source.DART);
    private static final Register ONE_CLASS = register(row("보통주", 1_000, 0));

    @Test
    @DisplayName("지배기업 자신의 별도 신종자본증권이 H다 — 연결 값(자회사 몫 포함)이 아니다(롯데지주 연결 772.1B · 별도 623.1B 꼴)")
    void separateStatementDecidesTheParentShare() {
        List<Fact> facts = List.of(consolidatedEquity(D, 10_000), hybrid(Basis.CONSOLIDATED, D, 772), hybrid(Basis.SEPARATE, D, 623),
                separateEquity(D, 8_000));
        Equity e = KrCommonEquity.judge(D, 2026, facts, issuer(facts), Map.of(2025, ONE_CLASS));
        assertThat(e.branch()).isEqualTo("kr");
        assertThat(e.hybrid()).isEqualByComparingTo("623");
        assertThat(e.fraction()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("연결에만 있고 그 날짜 별도 재무제표에 없으면 자회사 것이다(H = 0 · 현대차 · 한화에어로) — 별도 재무제표가 없으면 못 정함")
    void consolidatedOnlyHybridIsTheSubsidiarys() {
        List<Fact> withSeparate = List.of(consolidatedEquity(D, 10_000), hybrid(Basis.CONSOLIDATED, D, 300), separateEquity(D, 8_000));
        assertThat(KrCommonEquity.judge(D, 2026, withSeparate, issuer(withSeparate), Map.of(2025, ONE_CLASS)).hybrid()).isEqualByComparingTo("0");

        List<Fact> withoutSeparate = List.of(consolidatedEquity(D, 10_000), hybrid(Basis.CONSOLIDATED, D, 300));
        assertThat(KrCommonEquity.judge(D, 2026, withoutSeparate, issuer(withoutSeparate), Map.of(2025, ONE_CLASS)).branch())
                .isEqualTo("kr-open-h");
    }

    @Test
    @DisplayName("별도 태그가 빠진 판은 연결 값이 같으면 앞 판에서 짝지은 별도 값을 잇는다(HL만도 2026 분기 — 연결 289.0 · 별도 199.3)")
    void separateValueIsCarriedWhenTheConsolidatedValueIsUnchanged() {
        List<Fact> annual = List.of(consolidatedEquity(FY, 9_000), hybrid(Basis.CONSOLIDATED, FY, 289), hybrid(Basis.SEPARATE, FY, 199),
                separateEquity(FY, 7_000));
        List<Fact> quarter = List.of(consolidatedEquity(D, 9_100), hybrid(Basis.CONSOLIDATED, D, 289), separateEquity(D, 7_100));
        Issuer issuer = Issuer.of(List.of(entry(2025, annual), entry(2026, quarter)));
        assertThat(KrCommonEquity.judge(D, 2026, quarter, issuer, Map.of(2025, ONE_CLASS)).hybrid()).isEqualByComparingTo("199");
    }

    @Test
    @DisplayName("잔액 태그가 어느 기저에도 없으면 앞의 별도 확인값을 잇는다(에코프로비엠 2025 분기 꼴) — 움직임이 있으면 기초 + 발행 − 상환(두 움직임이 다 실릴 때만)")
    void balanceWithoutTagCarriesTheLastConfirmedValue() {
        List<Fact> annual = List.of(consolidatedEquity(FY, 9_000), hybrid(Basis.SEPARATE, FY, 334), separateEquity(FY, 7_000));
        List<Fact> quiet = List.of(consolidatedEquity(D, 9_100), separateEquity(D, 7_100));
        Issuer issuer = Issuer.of(List.of(entry(2025, annual), entry(2026, quiet)));
        assertThat(KrCommonEquity.judge(D, 2026, quiet, issuer, Map.of(2025, ONE_CLASS)).hybrid()).isEqualByComparingTo("334");

        List<Fact> repaid = new ArrayList<>(quiet);
        repaid.add(flow("RepaymentOfHybridBond", Basis.SEPARATE, D, 100));
        assertThat(KrCommonEquity.judge(D, 2026, repaid, Issuer.of(List.of(entry(2025, annual), entry(2026, repaid))), Map.of(2025, ONE_CLASS))
                .branch()).as("기초가 있는데 발행 움직임이 안 실렸다 — 식이 안 닫힌다").isEqualTo("kr-open-h");
        repaid.add(flow("IssueOfHybridBond", Basis.SEPARATE, D, 0));
        assertThat(KrCommonEquity.judge(D, 2026, repaid, Issuer.of(List.of(entry(2025, annual), entry(2026, repaid))), Map.of(2025, ONE_CLASS))
                .hybrid()).as("기초 334 + 발행 0 − 상환 100").isEqualByComparingTo("234");
    }

    @Test
    @DisplayName("첫 발행은 잔액 태그가 없어도 기초 0 + 발행으로 선다(파크시스템스 2026 반기 꼴) · 앞에 증거가 있는데 기초 확인값이 없으면 못 정함")
    void firstIssuanceRollsForwardFromZero() {
        List<Fact> issued = List.of(consolidatedEquity(D, 9_000), separateEquity(D, 7_000), flow("IssueOfHybridBond", Basis.SEPARATE, D, 106));
        assertThat(KrCommonEquity.judge(D, 2026, issued, issuer(issued), Map.of(2025, ONE_CLASS)).hybrid()).isEqualByComparingTo("106");

        List<Fact> older = List.of(flow("DividendToHybridBond", Basis.SEPARATE, LocalDate.of(2024, 12, 31), 3));
        assertThat(KrCommonEquity.judge(D, 2026, issued, Issuer.of(List.of(entry(2024, older), entry(2026, issued))),
                Map.of(2025, ONE_CLASS)).branch()).isEqualTo("kr-open-h");
    }

    @Test
    @DisplayName("확인값이 없는데 지배기업 자신의 신종자본증권 흐름이 있으면 못 정함 · 흐름도 없으면 0")
    void ownHybridFlowWithoutBalanceIsOpen() {
        List<Fact> flows = List.of(consolidatedEquity(D, 9_000), separateEquity(D, 7_000), flow("DividendToHybridBond", Basis.SEPARATE, D, 5));
        assertThat(KrCommonEquity.judge(D, 2026, flows, issuer(flows), Map.of(2025, ONE_CLASS)).branch()).isEqualTo("kr-open-h");

        List<Fact> none = List.of(consolidatedEquity(D, 9_000), separateEquity(D, 7_000));
        Equity e = KrCommonEquity.judge(D, 2026, none, issuer(none), Map.of(2025, ONE_CLASS));
        assertThat(e.hybrid()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("우선주 몫: 2계층 EPS(참가 지분)면 보통주 유통 ÷ 전 종류 유통 · 비중 2% 안이면 같은 비로 · 그 밖은 못 정함")
    void fractionNeedsTheLedgerToCarryTheClassAsParticipating() {
        Register twoClasses = register(row("보통주", 900, 0), row("우선주", 100, 0));
        List<Fact> plain = List.of(consolidatedEquity(D, 9_000), separateEquity(D, 7_000));
        assertThat(KrCommonEquity.judge(D, 2026, plain, issuer(plain), Map.of(2025, twoClasses)).branch()).isEqualTo("kr-open-c");

        List<Fact> participating = new ArrayList<>(plain);
        participating.add(new Fact(Source.DART, "BasicEarningsLossPerSharePreferredStock", LocalDate.of(2026, 1, 1), D,
                Basis.CONSOLIDATED, List.of(), BigDecimal.valueOf(1_010)));
        assertThat(KrCommonEquity.judge(D, 2026, participating, issuer(participating), Map.of(2025, twoClasses)).fraction())
                .isEqualByComparingTo("0.9");

        Register tiny = register(row("보통주", 9_990, 0), row("우선주", 10, 0));
        assertThat(KrCommonEquity.judge(D, 2026, plain, issuer(plain), Map.of(2025, tiny)).fraction()).isEqualByComparingTo("0.999");

        Register allTreasury = register(row("보통주", 900, 0), row("우선주", 100, 100));
        assertThat(KrCommonEquity.judge(D, 2026, plain, issuer(plain), Map.of(2025, allTreasury)).fraction()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("지배기업 자신의 상환전환우선주 부채가 있으면 그 종류는 자본 밖이다(c = 1) — 보통주 밖 종류가 둘이면 못 정함")
    void redeemableConvertiblePreferredLiabilityIsOutsideEquity() {
        List<Fact> facts = List.of(consolidatedEquity(D, 9_000), separateEquity(D, 7_000),
                new Fact(Source.DART, "CurrentPortionOfConvertibleRedeemablePreferredStockLiabilities", null, D, Basis.SEPARATE, List.of(),
                        BigDecimal.valueOf(18)));
        Register twoClasses = register(row("보통주", 900, 0), row("상환전환우선주", 100, 0));
        assertThat(KrCommonEquity.judge(D, 2026, facts, issuer(facts), Map.of(2025, twoClasses)).fraction()).isEqualByComparingTo("1");

        Register threeClasses = register(row("보통주", 800, 0), row("우선주", 100, 0), row("상환전환우선주", 100, 0));
        assertThat(KrCommonEquity.judge(D, 2026, facts, issuer(facts), Map.of(2025, threeClasses)).branch()).isEqualTo("kr-open-c");
    }

    @Test
    @DisplayName("총자본이 곧 지배 자본인 판은 kr-s — 별도재무제표만 낸 판 · 연결 지배 성분 = 자본총계(코리안리 2023 3분기)")
    void totalIsControllingWhenTheLedgerSaysSo() {
        List<Fact> separateOnly = List.of(new Fact(Source.IFRS, "Equity", null, D, Basis.NONE, List.of(), BigDecimal.valueOf(5_000)));
        assertThat(KrCommonEquity.judge(D, 2026, separateOnly, issuer(separateOnly), Map.of(2025, ONE_CLASS)).branch()).isEqualTo("kr-s");

        List<Fact> noMinority = List.of(consolidatedEquity(D, 9_000), separateEquity(D, 7_000),
                new Fact(Source.IFRS, "Equity", null, D, Basis.CONSOLIDATED,
                        List.of(new Member("ComponentsOfEquityAxis", "EquityAttributableToOwnersOfParentMember", Source.IFRS)),
                        BigDecimal.valueOf(9_000)));
        assertThat(KrCommonEquity.judge(D, 2026, noMinority, issuer(noMinority), Map.of(2025, ONE_CLASS)).branch()).isEqualTo("kr-s");
    }

    @Test
    @DisplayName("판 재료가 없으면 못 정함")
    void missingFilingIsOpen() {
        assertThat(KrCommonEquity.judge(D, 2026, null, issuer(List.of()), Map.of(2025, ONE_CLASS))).isEqualTo(Equity.OPEN);
    }

    // ---- 재료 ----

    private static Issuer issuer(List<Fact> facts) {
        return Issuer.of(List.of(entry(2026, facts)));
    }

    private static Map.Entry<Integer, List<Fact>> entry(int year, List<Fact> facts) {
        return new AbstractMap.SimpleEntry<>(year, facts);
    }

    private static Fact hybrid(Basis basis, LocalDate day, long value) {
        return new Fact(Source.IFRS, "Equity", null, day, basis, List.of(HYBRID_COMPONENT), BigDecimal.valueOf(value));
    }

    private static Fact flow(String concept, Basis basis, LocalDate end, long value) {
        return new Fact(Source.DART, concept, end.withDayOfYear(1), end, basis, List.of(), BigDecimal.valueOf(value));
    }

    private static Fact consolidatedEquity(LocalDate day, long value) {
        return new Fact(Source.IFRS, "Equity", null, day, Basis.CONSOLIDATED, List.of(), BigDecimal.valueOf(value));
    }

    private static Fact separateEquity(LocalDate day, long value) {
        return new Fact(Source.IFRS, "Equity", null, day, Basis.SEPARATE, List.of(), BigDecimal.valueOf(value));
    }

    private static ShareRow row(String label, long issued, long treasury) {
        return new ShareRow(label, BigDecimal.valueOf(issued), BigDecimal.valueOf(treasury));
    }

    private static Register register(ShareRow... rows) {
        List<ShareRow> all = new ArrayList<>(List.of(rows));
        long issued = 0;
        long treasury = 0;
        for (ShareRow r : rows) {
            issued += r.issued().longValue();
            treasury += r.treasury().longValue();
        }
        all.add(row("합계", issued, treasury));
        return Register.of(LocalDate.of(2025, 12, 31), all);
    }
}
