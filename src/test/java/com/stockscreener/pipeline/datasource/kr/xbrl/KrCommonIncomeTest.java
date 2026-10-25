package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Member;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Source;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Judged;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Period;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.Register;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.RowIncome;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncome.ShareRow;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Resolved;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrCommonIncomeRows.Row;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KrCommonIncomeTest {

    private static final Period Q2 = new Period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30));
    private static final Period H1 = new Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
    private static final LocalDate END = LocalDate.of(2026, 6, 30);
    private static final LocalDate REGISTER_DAY = LocalDate.of(2025, 12, 31);
    private static final Member ORDINARY = new Member("ClassesOfShareCapitalAxis", "OrdinarySharesMember", Source.IFRS);
    private static final KrCommonIncome.Multiple NO_ACTIONS = (from, to) -> 1.0;

    /** 보통주만 1,000만 주(자기주식 0). */
    private static final Register COMMON_ONLY = register(row("보통주", 10_000_000, 0));
    /** 보통주 1,000만 · 우선주 100만. */
    private static final Register WITH_PREFERRED = register(row("의결권 있는 주식(보통주)", 10_000_000, 0),
            row("의결권 없는 주식", 1_000_000, 0));

    @Test
    void noInstrumentsAndTheEpsIdentityClosesIsTheControllingIncome() {
        // 지배 NI 100억 · EPS 1,000원 × 1,000만 주 = 100억.
        Judged j = judge(rowNi(10_000_000_000L), COMMON_ONLY,
                owners(10_000_000_000L), eps(1000), weighted(10_000_000));

        assertThat(j.branch()).isEqualTo("kr-ni");
        assertThat(j.common()).isEqualByComparingTo("10000000000");
        assertThat(j.payoutBase()).isEqualByComparingTo("10000000000");
        assertThat(j.statement()).isEqualTo('C');
    }

    @Test
    void theLedgerEpsWinsOverTheStructureWhenTheIdentityFails() {
        // EPS 990원 × 1,000만 = 99억 — 지배 NI 100억과 0.5W(5백만) 밖으로 갈린다 · 지분상품 증거는 없다(NAVER FY2024 꼴).
        // 원장 EPS가 보통주 몫을 직접 밝히므로 그 값을 따른다 · 신종자본증권 증거가 없어 배당성향 분모는 지배 NI 그대로다.
        Judged j = judge(rowNi(10_000_000_000L), COMMON_ONLY,
                owners(10_000_000_000L), eps(990), weighted(10_000_000));

        assertThat(j.branch()).isEqualTo("exw");
        assertThat(j.common()).isEqualByComparingTo("9900000000");
        assertThat(j.payoutBase()).isEqualByComparingTo("10000000000");
    }

    @Test
    void hybridEvidenceMakesTheValueEpsTimesWeightedEvenWhenTheIdentityWouldClose() {
        // 신종자본증권 이자 개념만 있고 HybridBonds 잔액 태그가 없는 꼴(GS건설 2026 H1) — 그래도 지분상품 있음이다.
        Judged j = judge(rowNi(10_000_000_000L), COMMON_ONLY,
                owners(10_000_000_000L), eps(900), weighted(10_000_000), hybridInterest(1_000_000_000L));

        assertThat(j.branch()).isEqualTo("exw");
        assertThat(j.common()).isEqualByComparingTo("9000000000");
        // 명부가 한 종류(우선주 없음) — 배당성향 분모는 보통주 몫 + 0.
        assertThat(j.payoutBase()).isEqualByComparingTo("9000000000");
        assertThat(j.payoutBranch()).isEqualTo("c+0");
    }

    @Test
    void theDirectOrdinaryTagRefinesTheProductOnlyInsideTheRoundingBand() {
        Fact inside = fact(Source.IFRS, "ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity", Q2, List.of(), 9_003_000_000L);
        Judged refined = judge(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(900),
                weighted(10_000_000), hybridInterest(1L), inside);
        assertThat(refined.common()).isEqualByComparingTo("9003000000");

        // 신종자본증권 차감 전 줄을 「보통주 귀속」으로 단 꼴(롯데지주) — 반올림 밖이라 안 받는다.
        Fact outside = fact(Source.IFRS, "ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity", Q2, List.of(), 9_900_000_000L);
        Judged kept = judge(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(900),
                weighted(10_000_000), hybridInterest(1L), outside);
        assertThat(kept.common()).isEqualByComparingTo("9000000000");
    }

    @Test
    void preferredClassesOnTheStandardAxisAreSummedForThePayoutBase() {
        Member first = new Member("ClassesOfShareCapitalAxis", "FirstPreferredSharesMember", Source.ENTITY);
        Member second = new Member("ClassesOfShareCapitalAxis", "SecondPreferredSharesMember", Source.ENTITY);
        Judged j = judge(rowNi(10_000_000_000L), WITH_PREFERRED,
                owners(10_000_000_000L), eps(800), weighted(10_000_000), hybridInterest(5L),
                fact(Source.DART, "BasicEarningsLossPerSharePreferredStock", Q2, List.of(first), 850),
                fact(Source.IFRS, PREF_W, Q2, List.of(first), 600_000),
                fact(Source.DART, "BasicEarningsLossPerSharePreferredStock", Q2, List.of(second), 820),
                fact(Source.IFRS, PREF_W, Q2, List.of(second), 400_000));

        assertThat(j.branch()).isEqualTo("exw");
        assertThat(j.common()).isEqualByComparingTo("8000000000");
        // 80억 + 850 × 60만 + 820 × 40만 = 88억 3,800만.
        assertThat(j.payoutBase()).isEqualByComparingTo("8838000000");
        assertThat(j.payoutBranch()).isEqualTo("c+p");
    }

    @Test
    void aPreferredConceptOnTheOrdinaryMemberIsAMistagNotAZeroShare() {
        // 한화 2026 H1 — 우선 가중평균 0을 보통주 구성원에 달았다. 0으로 셈하면 분모가 보통주 몫만 된다.
        Judged j = judge(rowNi(10_000_000_000L), WITH_PREFERRED,
                owners(10_000_000_000L), eps(800), weighted(10_000_000), hybridInterest(5L),
                fact(Source.DART, "BasicEarningsLossPerSharePreferredStock", Q2, List.of(), 850),
                fact(Source.IFRS, PREF_W, Q2, List.of(ORDINARY), 0));

        assertThat(j.common()).isEqualByComparingTo("8000000000");
        assertThat(j.payoutBase()).isNull();
        assertThat(j.payoutBranch()).isEqualTo("none:pref-mistag");
    }

    @Test
    void withoutHybridEvidenceThePayoutBaseIsTheControllingIncomeEvenForPreferredIssuers() {
        Judged j = judge(rowNi(10_000_000_000L), WITH_PREFERRED,
                owners(10_000_000_000L), eps(800), weighted(10_000_000));

        assertThat(j.branch()).isEqualTo("exw");
        assertThat(j.payoutBase()).isEqualByComparingTo("10000000000");
        assertThat(j.payoutBranch()).isEqualTo("ni");
    }

    @Test
    void theAnchorFallsBackToConsolidatedProfitWhenThereIsNoAttributionLine() {
        Fact profit = fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_000_000_000L);
        Judged j = judge(rowNi(10_000_000_000L), COMMON_ONLY, profit, eps(1000), weighted(10_000_000));
        assertThat(j.branch()).isEqualTo("kr-ni");

        Judged off = judge(rowNi(12_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(10_000_000));
        assertThat(off.branch()).isEqualTo("none:anchor");
    }

    @Test
    void aConsolidatedRowWithoutControllingIncomeTakesTheControllingLineFromTheSameFiling() {
        // 고려아연 꼴 — 행은 연결(행 NI = 연결 순이익)인데 지배 NI가 비었다. 그 판이 지배 귀속을 표준으로 주면 그 값이 지배 NI다.
        Fact profit = fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_300_000_000L);
        Judged composed = KrCommonIncome.judge(Q2, new RowIncome(null, BigDecimal.valueOf(10_300_000_000L)),
                List.of(profit, owners(10_000_000_000L), eps(1000), weighted(10_000_000)), COMMON_ONLY, null, NO_ACTIONS);
        assertThat(composed.branch()).isEqualTo("kr-ni");
        assertThat(composed.common()).isEqualByComparingTo("10000000000");

        // 지배 귀속이 그 판에 없으면 정하지 않는다 — 연결 순이익(비지배 포함)을 지배로 읽지 않는다.
        Judged missing = KrCommonIncome.judge(Q2, new RowIncome(null, BigDecimal.valueOf(10_300_000_000L)),
                List.of(profit, eps(1000), weighted(10_000_000)), COMMON_ONLY, null, NO_ACTIONS);
        assertThat(missing.branch()).isEqualTo("none:no-controlling");
    }

    @Test
    void theOwnersShareMayComeFromTheEquityStatementAxis() {
        // NAVER 2018 반기 꼴 — 손익계산서에 표준 지배 귀속 줄이 없고 자본변동표의 지배주주 지분 구성원으로만 온다.
        Fact profit = fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_300_000_000L);
        Fact equity = fact(Source.IFRS, "ProfitLoss", Q2,
                List.of(new Member("ComponentsOfEquityAxis", "EquityAttributableToOwnersOfParentMember", Source.IFRS)), 10_000_000_000L);
        Judged j = judge(rowNi(10_000_000_000L), COMMON_ONLY, profit, equity, eps(1000), weighted(10_000_000));
        assertThat(j.branch()).isEqualTo("kr-ni");
    }

    @Test
    void aSeparateOnlyRowAnchorsOnSeparateProfit() {
        Fact separate = new Fact(Source.IFRS, "ProfitLoss", Q2.start(), Q2.end(), Basis.SEPARATE, List.of(),
                BigDecimal.valueOf(5_000_000_000L));
        Fact sepEps = new Fact(Source.IFRS, "BasicEarningsLossPerShare", Q2.start(), Q2.end(), Basis.SEPARATE, List.of(),
                BigDecimal.valueOf(500));
        Fact sepW = new Fact(Source.IFRS, "WeightedAverageShares", Q2.start(), Q2.end(), Basis.SEPARATE, List.of(),
                BigDecimal.valueOf(10_000_000));
        Judged j = KrCommonIncome.judge(Q2, new RowIncome(null, BigDecimal.valueOf(5_000_000_000L)),
                List.of(separate, sepEps, sepW), COMMON_ONLY, null, NO_ACTIONS);

        assertThat(j.branch()).isEqualTo("kr-ni");
        assertThat(j.statement()).isEqualTo('S');
        assertThat(j.common()).isEqualByComparingTo("5000000000");
    }

    @Test
    void theWeightedCheckFollowsCorporateActionsBetweenTheRegisterAndThePeriodEnd() {
        // 5:1 분할(010120 2026-04-13) — W 5,000만이 명부 1,000만의 5배지만 배수를 곱하면 맞다.
        List<Fact> facts = List.of(owners(50_000_000_000L), eps(1000), weighted(50_000_000), hybridInterest(1L));
        Judged split = KrCommonIncome.judge(Q2, rowNi(50_000_000_000L), facts, COMMON_ONLY, null,
                (from, to) -> from.isBefore(LocalDate.of(2026, 4, 13)) && !to.isBefore(LocalDate.of(2026, 4, 13)) ? 5.0 : 1.0);
        assertThat(split.branch()).isEqualTo("exw");

        Judged unexplained = KrCommonIncome.judge(Q2, rowNi(50_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS);
        assertThat(unexplained.branch()).isEqualTo("none:w-unit");
    }

    @Test
    void aWeightedAverageThatCountsPreferredSharesAsCommonIsHidden() {
        // NH투자증권 꼴 — W가 보통주 + 우선주 유통(1,100만)과 같다 · 보통주 밖 비중 9.1%.
        Judged j = judge(rowNi(11_000_000_000L), WITH_PREFERRED,
                owners(11_000_000_000L), eps(1000), weighted(11_000_000));

        assertThat(j.branch()).isEqualTo("none:w-includes-pref");
    }

    @Test
    void theWitnessRejectsAMissedAllocationWhenThereIsNoWeightedAverage() {
        // 지분상품 증거가 하나도 없는데 E × 기말 주식수가 지배 NI의 15분의 1이다(GS건설 Q2 꼴 · 이자 개념을 못 봤다면).
        Fact outstanding = new Fact(Source.IFRS, "NumberOfSharesOutstanding", null, END, Basis.CONSOLIDATED, List.of(ORDINARY),
                BigDecimal.valueOf(10_000_000));
        Judged j = judge(rowNi(3_000_000_000L), COMMON_ONLY, owners(3_000_000_000L), eps(20), outstanding);
        assertThat(j.branch()).isEqualTo("none:witness");

        Judged fine = judge(rowNi(3_000_000_000L), COMMON_ONLY, owners(3_000_000_000L), eps(300), outstanding);
        assertThat(fine.branch()).isEqualTo("kr-ni");
    }

    @Test
    void withoutARegisterTheStructureIsUndecided() {
        Judged j = KrCommonIncome.judge(Q2, rowNi(3_000_000_000L), List.of(owners(3_000_000_000L), eps(300)),
                null, null, NO_ACTIONS);
        assertThat(j.branch()).isEqualTo("none:no-register");
    }

    @Test
    void anEntityMemberNamedLikeAStandardMemberIsNotACandidate() {
        Member fake = new Member("ClassesOfShareCapitalAxis", "OrdinarySharesMember", Source.ENTITY);
        Judged j = judge(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L),
                fact(Source.IFRS, "BasicEarningsLossPerShare", Q2, List.of(fake), 800), weighted(10_000_000));
        assertThat(j.branch()).isEqualTo("none:no-eps");
    }

    @Test
    void registerSummaryIgnoresLabels() {
        Register r = register(row("의결권 있는 주식(보통주)", 9_000_000, 100_000), row("배당우선 전환주식(2종 종류주식)", 500_000, 0),
                row("비고", 0, 0));
        assertThat(r.multiClass()).isTrue();
        assertThat(r.commonOutstanding()).isEqualByComparingTo("8900000");
        assertThat(r.totalOutstanding()).isEqualByComparingTo("9400000");

        assertThat(Register.of(REGISTER_DAY, List.of(new ShareRow("보통주", BigDecimal.TEN, BigDecimal.ZERO)))).isNull();
    }

    // ---- 행 해석: Q4 · 누계 · 판 짝 ----

    @Test
    void fourthQuarterIsTheAnnualShareLessTheThreeQuarterShares() {
        List<Row> rows = new ArrayList<>();
        long[] quarterNi = {1_000_000_000L, 2_000_000_000L, 3_000_000_000L};
        int[] quarterEps = {90, 180, 270};
        for (int q = 1; q <= 3; q++) {
            Period p = quarter(2025, q);
            rows.add(new Row(q, 2025, "Q" + q, p.start(), p.end(), p.end().plusMonths(1), BigDecimal.valueOf(quarterNi[q - 1]),
                    null, exwFacts(p, quarterNi[q - 1], quarterEps[q - 1])));
        }
        Period fy = new Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        rows.add(new Row(5, 2025, "FY", fy.start(), fy.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(10_000_000_000L), null,
                exwFacts(fy, 10_000_000_000L, 900)));
        Period q4 = quarter(2025, 4);
        rows.add(new Row(4, 2025, "Q4", q4.start(), q4.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(4_000_000_000L), null, null));

        Map<Long, Resolved> byId = byId(KrCommonIncomeRows.resolve(rows, Map.of(2024, COMMON_ONLY), NO_ACTIONS));

        assertThat(byId.get(1L).branch()).isEqualTo("exw");
        // FY 90억 − (9억 + 18억 + 27억) = 36억.
        assertThat(byId.get(4L).branch()).isEqualTo("q4");
        assertThat(byId.get(4L).common()).isEqualByComparingTo("3600000000");
    }

    @Test
    void fourthQuarterIsHiddenWhenAQuarterIsUndecidedOrVintagesDoNotClose() {
        List<Row> rows = new ArrayList<>();
        for (int q = 1; q <= 3; q++) {
            Period p = quarter(2025, q);
            rows.add(new Row(q, 2025, "Q" + q, p.start(), p.end(), p.end().plusMonths(1), BigDecimal.valueOf(1_000_000_000L), null,
                    q == 2 ? null : exwFacts(p, 1_000_000_000L, 90)));
        }
        Period fy = new Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        rows.add(new Row(5, 2025, "FY", fy.start(), fy.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(4_000_000_000L), null,
                exwFacts(fy, 4_000_000_000L, 360)));
        Period q4 = quarter(2025, 4);
        rows.add(new Row(4, 2025, "Q4", q4.start(), q4.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(1_000_000_000L), null, null));
        Map<Long, Resolved> undecided = byId(KrCommonIncomeRows.resolve(rows, Map.of(2024, COMMON_ONLY), NO_ACTIONS));
        assertThat(undecided.get(2L).branch()).isEqualTo("nokey");
        assertThat(undecided.get(4L).branch()).isEqualTo("none:q4");

        // Q4 행 지배 NI가 FY − ΣQ와 다르다 — 어댑터가 다른 판의 분기를 썼다.
        rows.set(1, new Row(2, 2025, "Q2", quarter(2025, 2).start(), quarter(2025, 2).end(), LocalDate.of(2025, 8, 1),
                BigDecimal.valueOf(1_000_000_000L), null, exwFacts(quarter(2025, 2), 1_000_000_000L, 90)));
        rows.set(4, new Row(4, 2025, "Q4", q4.start(), q4.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(900_000_000L), null, null));
        Map<Long, Resolved> mixed = byId(KrCommonIncomeRows.resolve(rows, Map.of(2024, COMMON_ONLY), NO_ACTIONS));
        assertThat(mixed.get(4L).branch()).isEqualTo("none:q4-vintage");
    }

    @Test
    void fourthQuarterFallsBackToTheNineMonthCumulativeWhenAQuarterIsUndecided() {
        // 1분기 판에 가중평균이 없다(금융지주 2025 꼴) — 3분기 판의 9개월 누계 몫으로 Q4 = FY − 9개월.
        List<Row> rows = new ArrayList<>();
        Period q1 = quarter(2025, 1);
        rows.add(new Row(1, 2025, "Q1", q1.start(), q1.end(), LocalDate.of(2025, 5, 15), BigDecimal.valueOf(1_000_000_000L), null,
                List.of(fact(Source.IFRS, "ProfitLossAttributableToOwnersOfParent", q1, List.of(), 1_000_000_000L),
                        fact(Source.IFRS, "BasicEarningsLossPerShare", q1, List.of(ORDINARY), 90),
                        fact(Source.DART, "HybridBonds", new Period(null, q1.end()), List.of(), 1L))));
        Period q2 = quarter(2025, 2);
        rows.add(new Row(2, 2025, "Q2", q2.start(), q2.end(), LocalDate.of(2025, 8, 14), BigDecimal.valueOf(2_000_000_000L), null,
                exwFacts(q2, 2_000_000_000L, 180)));
        Period q3 = quarter(2025, 3);
        Period nine = new Period(q1.start(), q3.end());
        List<Fact> third = new ArrayList<>(exwFacts(q3, 3_000_000_000L, 270));
        third.add(fact(Source.IFRS, "BasicEarningsLossPerShare", nine, List.of(ORDINARY), 540));
        third.add(fact(Source.IFRS, "WeightedAverageShares", nine, List.of(ORDINARY), 10_000_000));
        rows.add(new Row(3, 2025, "Q3", q3.start(), q3.end(), LocalDate.of(2025, 11, 14), BigDecimal.valueOf(3_000_000_000L), null, third));
        Period fy = new Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        rows.add(new Row(5, 2025, "FY", fy.start(), fy.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(10_000_000_000L), null,
                exwFacts(fy, 10_000_000_000L, 900)));
        Period q4 = quarter(2025, 4);
        rows.add(new Row(4, 2025, "Q4", q4.start(), q4.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(4_000_000_000L), null, null));

        Map<Long, Resolved> byId = byId(KrCommonIncomeRows.resolve(rows, Map.of(2024, COMMON_ONLY), NO_ACTIONS));

        assertThat(byId.get(1L).common()).isNull();
        // FY 90억 − 9개월 540원 × 1,000만 = 54억 → 36억.
        assertThat(byId.get(4L).branch()).isEqualTo("q4-cum");
        assertThat(byId.get(4L).common()).isEqualByComparingTo("3600000000");
    }

    @Test
    void cumulativeShareLessTheEarlierQuarterWhenOnlyTheHalfYearWeightedAverageIsGiven() {
        Period q1 = quarter(2026, 1);
        Row first = new Row(1, 2026, "Q1", q1.start(), q1.end(), LocalDate.of(2026, 5, 15), BigDecimal.valueOf(1_000_000_000L), null,
                exwFacts(q1, 1_000_000_000L, 90));
        // 반기 판: 3개월 W가 없고 누계 E · W만 — 누계 몫 19억 8천만 − 1분기 몫 9억 = 10억 8천만.
        List<Fact> half = List.of(owners(1_200_000_000L), eps(108), hybridInterest(1L),
                fact(Source.IFRS, "BasicEarningsLossPerShare", H1, List.of(ORDINARY), 198),
                fact(Source.IFRS, "WeightedAverageShares", H1, List.of(ORDINARY), 10_000_000));
        Row second = new Row(2, 2026, "Q2", Q2.start(), Q2.end(), LocalDate.of(2026, 8, 14), BigDecimal.valueOf(1_200_000_000L), null, half);

        Map<Long, Resolved> byId = byId(KrCommonIncomeRows.resolve(List.of(first, second), Map.of(2025, COMMON_ONLY), NO_ACTIONS));

        assertThat(byId.get(2L).branch()).isEqualTo("cum");
        assertThat(byId.get(2L).common()).isEqualByComparingTo("1080000000");
    }

    // ---- 재료 ----

    private static final String PREF_W =
            "WeightedAverageNumberOfInstrumentsUsedInCalculatingBasicEarningsLossPerInstrumentParticipatingEquityInstrumentsOtherThanOrdinaryShares";

    private static Judged judge(RowIncome row, Register prior, Fact... facts) {
        return KrCommonIncome.judge(Q2, row, List.of(facts), prior, null, NO_ACTIONS);
    }

    private static RowIncome rowNi(long controlling) {
        return new RowIncome(BigDecimal.valueOf(controlling), BigDecimal.valueOf(controlling));
    }

    private static Fact owners(long value) {
        return fact(Source.IFRS, "ProfitLossAttributableToOwnersOfParent", Q2, List.of(), value);
    }

    private static Fact eps(long value) {
        return fact(Source.IFRS, "BasicEarningsLossPerShare", Q2, List.of(ORDINARY), value);
    }

    private static Fact weighted(long value) {
        return fact(Source.IFRS, "WeightedAverageShares", Q2, List.of(ORDINARY), value);
    }

    private static Fact hybridInterest(long value) {
        return fact(Source.DART, "InterestExpenseForHybridBond", H1,
                List.of(new Member("ComponentsOfEquityAxis", "EquityAttributableToOwnersOfParentMember", Source.IFRS)), value);
    }

    private static List<Fact> exwFacts(Period p, long income, long eps) {
        return List.of(fact(Source.IFRS, "ProfitLossAttributableToOwnersOfParent", p, List.of(), income),
                fact(Source.IFRS, "BasicEarningsLossPerShare", p, List.of(ORDINARY), eps),
                fact(Source.IFRS, "WeightedAverageShares", p, List.of(ORDINARY), 10_000_000),
                fact(Source.DART, "HybridBonds", new Period(null, p.end()), List.of(), 1_000_000_000L));
    }

    private static Fact fact(Source source, String concept, Period p, List<Member> members, long value) {
        return new Fact(source, concept, p.start(), p.end(), Basis.CONSOLIDATED, members, BigDecimal.valueOf(value));
    }

    private static Period quarter(int year, int q) {
        LocalDate start = LocalDate.of(year, 3 * (q - 1) + 1, 1);
        return new Period(start, start.plusMonths(3).minusDays(1));
    }

    private static ShareRow row(String label, long issued, long treasury) {
        return new ShareRow(label, BigDecimal.valueOf(issued), BigDecimal.valueOf(treasury));
    }

    private static Register register(ShareRow... rows) {
        List<ShareRow> all = new ArrayList<>(List.of(rows));
        long issued = 0;
        long treasury = 0;
        for (ShareRow r : rows) {
            if (!"비고".equals(r.label())) {
                issued += r.issued().longValue();
                treasury += r.treasury().longValue();
            }
        }
        all.add(row("합계", issued, treasury));
        return Register.of(REGISTER_DAY, all);
    }

    private static Map<Long, Resolved> byId(List<Resolved> resolved) {
        Map<Long, Resolved> out = new java.util.HashMap<>();
        resolved.forEach(r -> out.put(r.row().id(), r));
        return out;
    }
}
