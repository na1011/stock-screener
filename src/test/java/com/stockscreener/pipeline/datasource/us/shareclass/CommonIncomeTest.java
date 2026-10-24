package com.stockscreener.pipeline.datasource.us.shareclass;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 보통주 귀속 이익 사다리 · 행 붙이기(탐색 {@code PERSHARE-NUM-PLAN-1024.md} v2.1). 수는 원장 꼴을 본뜬 것이다 — TPG(선언 NIC) · 은행(우선주
 * 배당) · HPE(부호) · CTVA 2023 Q1(EPS를 NIC 자리에) · GWW(배분 태그 없는 참가증권) · HON 2025 Q3(10-K가 다시 실은 분기 표) · ACA(잔액만
 * 실은 판).
 */
class CommonIncomeTest {

    private static final ClassFacts.Period Q = new ClassFacts.Period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30));
    private static final String A = "0000000001-26-000010";

    /** 한 공시 · 한 기간의 재료를 짓는다. */
    private static final class Ledger {
        final Map<String, IssuerFacts.Filing> filings = new LinkedHashMap<>();
        final Map<IssuerFacts.Key, Double> weighted = new HashMap<>();
        final Map<IssuerFacts.Key, Double> nic = new HashMap<>();
        final Map<IssuerFacts.Key, Double> totalEps = new HashMap<>();
        final Map<IssuerFacts.Key, Double> continuingEps = new HashMap<>();
        final Map<IssuerFacts.Key, Double> netIncome = new HashMap<>();
        final Map<IssuerFacts.Key, Double> preferred = new HashMap<>();
        final Map<IssuerFacts.Key, Double> participating = new HashMap<>();
        final Map<IssuerFacts.Key, Double> limitedPartners = new HashMap<>();
        final Map<String, Double> cover = new HashMap<>();

        Ledger filing(String accession, String form, LocalDate filed, Double coverShares) {
            filings.put(accession, new IssuerFacts.Filing(form, filed));
            if (coverShares != null) {
                cover.put(accession, coverShares);
            }
            return this;
        }

        IssuerFacts facts() {
            return new IssuerFacts(filings, weighted, totalEps, nic, netIncome, cover, IssuerFacts.Witnesses.NONE,
                    new IssuerFacts.Numerators(totalEps, continuingEps, netIncome, Map.of(), Map.of(), preferred, participating,
                            Map.of(), limitedPartners));
        }
    }

    private static Ledger quarter(double ni, double eps, double w) {
        Ledger l = new Ledger().filing(A, "10-Q", LocalDate.of(2026, 7, 30), w);
        IssuerFacts.Key k = new IssuerFacts.Key(A, Q);
        l.netIncome.put(k, ni);
        l.totalEps.put(k, eps);
        l.weighted.put(k, w);
        return l;
    }

    private static CommonIncome.Value judge(Ledger l) {
        return CommonIncome.judge(l.facts(), Map.of(), Map.of(), List.of(), List.of()).get(new IssuerFacts.Key(A, Q));
    }

    @Test
    void declaredCommonIncomeWinsWhenTheLedgerEpsAgrees() {
        // TPG 꼴 — 지배 NI 93.4M · 보통주 몫 72.7M(참가증권 배분) · EPS × W ≈ 72.7M
        Ledger l = quarter(93_400_000, 0.20, 363_500_000);
        l.nic.put(new IssuerFacts.Key(A, Q), 72_700_000d);
        CommonIncome.Value v = judge(l);
        assertThat(v.branch()).isEqualTo("decl");
        assertThat(v.common()).isEqualTo(72_700_000d);
    }

    @Test
    void declaredCommonIncomeThatContradictsTheLedgerEpsIsNotTaken() {
        // CTVA 2023 Q1 꼴 — NIC 자리에 EPS 값(0.84)이 실렸다. 배분 태그가 없고 NI가 EPS × W와 닫히므로 배분 0이다.
        Ledger l = quarter(593_000_000, 0.84, 706_000_000);
        l.nic.put(new IssuerFacts.Key(A, Q), 0.84);
        assertThat(judge(l).branch()).isEqualTo("ni");
    }

    @Test
    void declaredAllocationIsSubtractedWhenTheResultClosesWithTheLedgerEps() {
        // 은행 꼴 — 지배 NI 2,000M · 우선주 배당 120M · EPS 1.88 × 1,000M = 1,880M
        Ledger l = quarter(2_000_000_000, 1.88, 1_000_000_000);
        l.preferred.put(new IssuerFacts.Key(A, Q), 120_000_000d);
        CommonIncome.Value v = judge(l);
        assertThat(v.branch()).isEqualTo("adj");
        assertThat(v.common()).isEqualTo(1_880_000_000d);
    }

    @Test
    void controllingIncomeClosingWithTheLedgerEpsIsTheWitnessOfNoAllocation() {
        Ledger l = quarter(1_000_000_000, 2.00, 500_000_000);
        CommonIncome.Value v = judge(l);
        assertThat(v.branch()).isEqualTo("ni");
        assertThat(v.common()).isNull();
    }

    @Test
    void anAllocationTagBlocksTheNoAllocationWitness() {
        // 배분 태그가 있는데 NI − 배분이 안 닫히면 NI 폐합을 증인으로 받지 않는다(EPS가 작은 분기의 허용폭이 배분보다 크다 · 반증 안 5).
        Ledger l = quarter(1_000_000_000, 2.00, 500_000_000);
        l.preferred.put(new IssuerFacts.Key(A, Q), 900_000_000d);
        CommonIncome.Value v = judge(l);
        assertThat(v.branch()).isEqualTo("eps");
        assertThat(v.common()).isCloseTo(1_000_000_000d, within(1d));
    }

    @Test
    void ledgerEarningsPerShareTimesAVerifiedDenominatorIsTheCommonShareWhenNothingElseStands() {
        // GWW 꼴 — 참가증권 배분을 축 없는 태그 없이 EPS에만 반영했다: NI 480M · EPS 9.80 × 48M = 470.4M
        Ledger l = quarter(480_000_000, 9.80, 48_000_000);
        CommonIncome.Value v = judge(l);
        assertThat(v.branch()).isEqualTo("eps");
        assertThat(v.common()).isCloseTo(470_400_000d, within(1d));
    }

    @Test
    void anUnverifiedDenominatorOnlyWitnesses() {
        // 가중평균이 표지와 10배 넘게 갈린다(천 단위 오태깅) — 값으로 안 쓴다.
        Ledger l = quarter(480_000_000, 9.80, 48_000);
        l.cover.put(A, 48_000_000d);
        assertThat(judge(l).branch()).isEqualTo("epsw");
    }

    @Test
    void limitedPartnerAllocationClosingWithTheUnitEarningsIsTheCommonUnitShare() {
        // ET 꼴 — NI(지배) 1,000M · LP 배분 940M(GP · 우선 단위 몫 60M) · LP 단위 EPS 0.94 × 1,000M
        Ledger l = quarter(1_000_000_000, 0.94, 1_000_000_000);
        l.limitedPartners.put(new IssuerFacts.Key(A, Q), 940_000_000d);
        CommonIncome.Value v = judge(l);
        assertThat(v.branch()).isEqualTo("lp");
        assertThat(v.common()).isEqualTo(940_000_000d);
    }

    @Test
    void continuingEarningsPerShareMarksTheValueAsContinuing() {
        Ledger l = quarter(480_000_000, 0, 48_000_000);
        IssuerFacts.Key k = new IssuerFacts.Key(A, Q);
        l.totalEps.remove(k);
        l.continuingEps.put(k, 9.80);
        CommonIncome.Value v = judge(l);
        assertThat(v.basis()).isEqualTo(CommonIncome.Basis.C);
    }

    @Test
    void nothingToStandOnIsUndecided() {
        Ledger l = new Ledger().filing(A, "10-Q", LocalDate.of(2026, 7, 30), 48_000_000d);
        l.netIncome.put(new IssuerFacts.Key(A, Q), 480_000_000d);
        assertThat(judge(l).branch()).isEqualTo("none");
    }

    // ---------------------------------------------------------------- 행 붙이기

    private static CommonIncomeRows.Row row(String period, int rev, LocalDate start, LocalDate end, LocalDate published,
                                            String reportCode, long ni) {
        return new CommonIncomeRows.Row(2025, period, rev, start, end, published, reportCode, BigDecimal.valueOf(ni));
    }

    private static final LocalDate Q3S = LocalDate.of(2025, 7, 1);
    private static final LocalDate Q3E = LocalDate.of(2025, 9, 30);

    @Test
    void aRowOfALaterFilingThatCouldNotDecideTakesTheEarlierFilingsAllocation() {
        // HON 2025 Q3 꼴 — 10-K가 분기 표를 다시 실었는데 가중평균이 없어 못 정했다. 원 10-Q가 정한 배분(30M)을 그 행 지배 NI에서 뺀다.
        Map<IssuerFacts.Key, CommonIncome.Value> keys = new LinkedHashMap<>();
        keys.put(new IssuerFacts.Key("Q3", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(770_000_000d, CommonIncome.Basis.T, "decl", LocalDate.of(2025, 10, 23), 0, 800_000_000d));
        keys.put(new IssuerFacts.Key("K", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(null, CommonIncome.Basis.T, "none", LocalDate.of(2026, 2, 17)));
        CommonIncomeRows.Row restated = row("Q3", 1, Q3S, Q3E, LocalDate.of(2026, 2, 17), "10-K:K", 810_000_000);
        CommonIncomeRows.Resolved r = CommonIncomeRows.resolve(List.of(restated), keys).get(0);
        assertThat(r.branch()).isEqualTo("xfer");
        assertThat(r.common()).isEqualByComparingTo("780000000");
    }

    @Test
    void aRowWhoseFilingCarriedNoIncomeForThePeriodTakesTheLatestEarlierFiling() {
        // ACA 꼴 — 행의 공시(뒤 해 10-Q)가 그 기간의 잔액만 실었다(손익 키 없음) — 같은 기간을 실은 최신 앞 공시의 키.
        Map<IssuerFacts.Key, CommonIncome.Value> keys = new LinkedHashMap<>();
        keys.put(new IssuerFacts.Key("Q3", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(null, CommonIncome.Basis.T, "ni", LocalDate.of(2025, 10, 23)));
        CommonIncomeRows.Row row = row("Q3", 1, Q3S, Q3E, LocalDate.of(2026, 5, 1), "10-Q:LATER", 810_000_000);
        assertThat(CommonIncomeRows.resolve(List.of(row), keys).get(0).branch()).isEqualTo("ni");
    }

    @Test
    void ledgerEarningsThatConfirmTheRowsControllingIncomeWithinRoundingAreNoAllocation() {
        // A FY2015 꼴 — 키에 NI가 없어 EPS × W(399.6M)가 섰지만 행 지배 NI 401M이 반올림 폭(± 1.67M) 안이다.
        Map<IssuerFacts.Key, CommonIncome.Value> keys = Map.of(new IssuerFacts.Key("Q3", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(399_600_000d, CommonIncome.Basis.T, "eps", LocalDate.of(2025, 10, 23), 1_665_000, null));
        CommonIncomeRows.Row row = row("Q3", 0, Q3S, Q3E, LocalDate.of(2025, 10, 23), "10-Q:Q3", 401_000_000);
        assertThat(CommonIncomeRows.resolve(List.of(row), keys).get(0).branch()).isEqualTo("ni");
    }

    @Test
    void fourthQuarterSubtractsTheQuarterVintagesTheAdapterUsed() {
        // 어댑터의 Q4 = FY − 그 FY 공시일까지의 세 분기 판. 뒤 판(공시일이 FY 뒤)은 안 쓴다.
        LocalDate fyPub = LocalDate.of(2026, 2, 17);
        Map<IssuerFacts.Key, CommonIncome.Value> keys = new LinkedHashMap<>();
        keys.put(new IssuerFacts.Key("K", new ClassFacts.Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))),
                new CommonIncome.Value(3_700_000_000d, CommonIncome.Basis.T, "decl", fyPub, 0, 4_000_000_000d));
        keys.put(new IssuerFacts.Key("Q1", new ClassFacts.Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31))),
                new CommonIncome.Value(900_000_000d, CommonIncome.Basis.T, "decl", LocalDate.of(2025, 4, 25), 0, 975_000_000d));
        keys.put(new IssuerFacts.Key("Q2", new ClassFacts.Period(LocalDate.of(2025, 4, 1), LocalDate.of(2025, 6, 30))),
                new CommonIncome.Value(900_000_000d, CommonIncome.Basis.T, "decl", LocalDate.of(2025, 7, 25), 0, 975_000_000d));
        keys.put(new IssuerFacts.Key("Q3", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(900_000_000d, CommonIncome.Basis.T, "decl", LocalDate.of(2025, 10, 25), 0, 975_000_000d));
        keys.put(new IssuerFacts.Key("LATE", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(100d, CommonIncome.Basis.T, "decl", LocalDate.of(2026, 10, 25), 0, 975_000_000d));
        List<CommonIncomeRows.Row> rows = List.of(
                row("FY", 0, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), fyPub, "10-K:K", 4_000_000_000L),
                row("Q1", 0, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 25), "10-Q:Q1", 975_000_000),
                row("Q2", 0, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 6, 30), LocalDate.of(2025, 7, 25), "10-Q:Q2", 975_000_000),
                row("Q3", 0, Q3S, Q3E, LocalDate.of(2025, 10, 25), "10-Q:Q3", 975_000_000),
                row("Q3", 1, Q3S, Q3E, LocalDate.of(2026, 10, 25), "10-Q:LATE", 975_000_000),
                row("Q4", 0, LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), fyPub, "10-K:K", 1_075_000_000));
        CommonIncomeRows.Resolved q4 = CommonIncomeRows.resolve(rows, keys).get(5);
        assertThat(q4.branch()).isEqualTo("q4");
        assertThat(q4.common()).isEqualByComparingTo("1000000000");
    }

    @Test
    void aDenominatorTaggedInThousandsIsRescaledWhenExactlyOneScaleClosesWithTheNumerator() {
        // TEM 꼴 — 가중평균 179,917(천 단위) · EPS 0.03 · NI 5.642M · 표지 없음.
        Ledger l = new Ledger().filing(A, "10-Q", LocalDate.of(2026, 7, 30), null);
        IssuerFacts.Key k = new IssuerFacts.Key(A, Q);
        l.netIncome.put(k, 5_642_000d);
        l.totalEps.put(k, 0.03);
        l.weighted.put(k, 179_917d);
        assertThat(judge(l).branch()).isEqualTo("ni");
    }

    @Test
    void dilutedOnlyFilingsWitnessNoAllocation() {
        // POR 2025 Q1 꼴 — 기본 EPS 없이 희석만. 희석 EPS × 희석 W가 NI와 닫힌다.
        Ledger l = new Ledger().filing(A, "10-Q", LocalDate.of(2026, 7, 30), 109_514_333d);
        IssuerFacts.Key k = new IssuerFacts.Key(A, Q);
        l.netIncome.put(k, 100_000_000d);
        IssuerFacts base = l.facts();
        IssuerFacts cf = new IssuerFacts(base.filings(), base.weighted(), base.eps(), base.declaredNumerator(),
                base.proxyNumerator(), base.coverShares(),
                new IssuerFacts.Witnesses(Map.of(), Map.of(k, 109_683_000d), Map.of(), Map.of(k, 0.91), Map.of(), java.util.Set.of()),
                base.numerators());
        assertThat(CommonIncome.judge(cf, Map.of(), Map.of(), List.of(), List.of()).get(k).branch()).isEqualTo("ni");
    }

    @Test
    void ownKeysOfTheSamePeriodPreferTheDecidedOne() {
        // LSCC 2025 Q3 꼴 — 한 공시가 시작일만 하루 다른 키 둘(정함 · 못 정함)로 실었다.
        Map<IssuerFacts.Key, CommonIncome.Value> keys = new LinkedHashMap<>();
        keys.put(new IssuerFacts.Key("Q3", new ClassFacts.Period(Q3S.minusDays(1), Q3E)),
                new CommonIncome.Value(null, CommonIncome.Basis.T, "ni", LocalDate.of(2025, 10, 23)));
        keys.put(new IssuerFacts.Key("Q3", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(null, CommonIncome.Basis.T, "none", LocalDate.of(2025, 10, 23)));
        CommonIncomeRows.Row row = row("Q3", 0, Q3S, Q3E, LocalDate.of(2025, 10, 23), "10-Q:Q3", 2_794_000);
        assertThat(CommonIncomeRows.resolve(List.of(row), keys).get(0).branch()).isEqualTo("ni");
    }

    @Test
    void aTransferTakesTheEarlierFilingsRowIncomeWhenTheKeyHadNone() {
        // FTV 2020 Q4 꼴 — 앞 공시 키에 NI가 없다(선언 NIC만) — 그 공시가 실은 같은 기간 행의 지배 NI로 배분을 잰다.
        Map<IssuerFacts.Key, CommonIncome.Value> keys = new LinkedHashMap<>();
        keys.put(new IssuerFacts.Key("Q3", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(780_000_000d, CommonIncome.Basis.T, "decl", LocalDate.of(2025, 10, 23), 0, null));
        keys.put(new IssuerFacts.Key("K", new ClassFacts.Period(Q3S, Q3E)),
                new CommonIncome.Value(null, CommonIncome.Basis.T, "none", LocalDate.of(2026, 2, 17)));
        List<CommonIncomeRows.Row> rows = List.of(
                row("Q3", 0, Q3S, Q3E, LocalDate.of(2025, 10, 23), "10-Q:Q3", 800_000_000),
                row("Q3", 1, Q3S, Q3E, LocalDate.of(2026, 2, 17), "10-K:K", 810_000_000));
        CommonIncomeRows.Resolved r = CommonIncomeRows.resolve(rows, keys).get(1);
        assertThat(r.branch()).isEqualTo("xfer");
        assertThat(r.common()).isEqualByComparingTo("790000000");
    }

    @Test
    void aFourthQuarterWithoutQuarterVintagesFollowsAnUnallocatedYear() {
        // 상장 첫해(INGM · RKT 꼴) — 세 분기 판이 없다. FY 배분 0이면 Q4도 0이다(배분은 음수가 아니다).
        LocalDate fyPub = LocalDate.of(2026, 2, 17);
        Map<IssuerFacts.Key, CommonIncome.Value> keys = Map.of(
                new IssuerFacts.Key("K", new ClassFacts.Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))),
                new CommonIncome.Value(null, CommonIncome.Basis.T, "ni", fyPub));
        List<CommonIncomeRows.Row> rows = List.of(
                row("FY", 0, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), fyPub, "10-K:K", 4_000_000_000L),
                row("Q4", 0, LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), fyPub, "10-K:K", 1_000_000_000));
        assertThat(CommonIncomeRows.resolve(rows, keys).get(1).branch()).isEqualTo("ni");
    }
}
