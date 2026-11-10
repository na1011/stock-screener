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
    void r1ClosesNoWeightedRowsWhenEpsTimesRegisterSharesMeetsTheIncome() {
        // 우선주가 있어 지분상품 있음 · 3개월 W 없음 · E 1,000원 × 명부 보통주 1,000만 = 100억 = 지배 NI — 배분 0(국내 관문 켬 R1).
        Judged off = judge(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(1000));
        Judged on = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(1000));
        // 대조군 — 우선주가 몫을 가져가면 E × S가 그만큼 작아 닫히지 않는다(EPS 990원 · 1억 차 > 0.5S).
        Judged allocated = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(990));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:no-w");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("10000000000");
        assertThat(allocated.branch()).isEqualTo("none:no-w");
    }

    @Test
    void c1PicksTheClosingPairWhenTheLedgerCarriesTwoEps() {
        // 지분상품 없음 · 기본 EPS 개념에 1,000원과 950원이 함께 실렸다 · W 1,000만 — 1,000 × W만 지배 NI 100억과 0.5W 안에서 닫힌다(국내 관문 켬 C1).
        Judged off = judge(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), eps(950), weighted(10_000_000));
        Judged on = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), eps(950), weighted(10_000_000));
        // 대조군 — 닫히는 짝의 E가 둘로 갈리면(W 둘이 각각 다른 E와 닫힌다) 고르지 않는다.
        Judged split = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), eps(500), weighted(10_000_000),
                weighted(20_000_000));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:conflict");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("10000000000");
        assertThat(split.branch()).isEqualTo("none:conflict");
    }

    @Test
    void c1OnInstrumentRowsNeedsTheDroppedEpsToBeTheDilutedOne() {
        // 우선주가 있어 지분상품 있음 · E 1,000 · 950 · W 하나 — 버리는 950이 같은 기간 희석 EPS로도 실렸다(파마리서치 2025 FY 꼴 · C+⑵).
        Fact diluted = fact(Source.IFRS, "DilutedEarningsLossPerShare", Q2, List.of(ORDINARY), 950);
        Judged on = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(1000), eps(950), weighted(10_000_000),
                diluted);
        // 대조군 둘 — 버리는 E가 희석 EPS로 안 실렸다 · W 후보가 둘이다(지분상품 행은 진짜 짝이 원래 안 닫혀 닫히는 짝이 미끼일 수 있다).
        Judged notDiluted = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(1000), eps(950),
                weighted(10_000_000));
        Judged twoWeighted = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(1000), weighted(10_000_000),
                weighted(10_400_000));

        assertThat(on.branch()).isEqualTo("exw");
        assertThat(on.common()).isEqualByComparingTo("10000000000");
        assertThat(notDiluted.branch()).isEqualTo("none:conflict");
        assertThat(twoWeighted.branch()).isEqualTo("none:conflict");
    }

    @Test
    void e2UsesTheContinuingEpsWhenTheLedgerGivesNoTotalEps() {
        // 우선주가 있어 지분상품 있음 · 총 E 없음 · 계속영업 E 950원 × W 1,000만 = 95억(중단영업이 있는 해 · 국내 관문 켬 E2).
        Fact continuing = fact(Source.IFRS, "BasicEarningsLossPerShareFromContinuingOperations", Q2, List.of(ORDINARY), 950);
        Judged off = judge(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), continuing, weighted(10_000_000));
        Judged on = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), continuing, weighted(10_000_000));
        // 대조군 — W가 명부의 천 배(단위 오류)면 다른 E × W 갈래처럼 안 받는다.
        Judged unit = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), continuing, weighted(10_000_000_000L));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:no-eps");
        assertThat(on.branch()).isEqualTo("exw");
        assertThat(on.common()).isEqualByComparingTo("9500000000");
        assertThat(on.basis()).isEqualTo('C');
        assertThat(unit.branch()).isEqualTo("none:w-unit");
    }

    @Test
    void signDropsTheTotalEpsWhoseSignContradictsTheControllingIncome() {
        // 지배 NI 100억 · E 후보 −1,000 · +1,000(같은 크기 부호 상충 · 두산 2025 FY 꼴) — NI와 부호가 같은 +1,000만 남아 닫힌다(국내 관문 켬 SIGN).
        Judged off = judge(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), eps(-1000), weighted(10_000_000));
        Judged on = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), eps(-1000), weighted(10_000_000));
        // 대조군 — E 하나는 한 방향만 거른다: 지배 NI > 0 · E < 0은 지분상품 배분으로 정당하게 생겨 남긴다(롯데지주 2026 Q1 꼴).
        Judged negative = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(-26), weighted(10_000_000));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:conflict");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("10000000000");
        assertThat(negative.branch()).isEqualTo("exw");
        assertThat(negative.common()).isEqualByComparingTo("-260000000");
    }

    @Test
    void ncTakesTheConsolidatedIncomeWhenEquityShowsNoNoncontrollingInterest() {
        // 행 지배 NI가 빈 연결 행 — 연결 순이익 100억 · 기초(03-31) · 기말(06-30) 자본총계 = 지배 자본 · 비지배 손익 줄 없음(국내 관문 켬 NC-b).
        RowIncome row = new RowIncome(null, BigDecimal.valueOf(10_000_000_000L));
        Fact profit = fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_000_000_000L);
        List<Fact> equity = List.of(instant("Equity", LocalDate.of(2026, 3, 31), 500), instant("EquityAttributableToOwnersOfParent",
                LocalDate.of(2026, 3, 31), 500), instant("Equity", END, 600), instant("EquityAttributableToOwnersOfParent", END, 600));
        java.util.List<Fact> facts = new java.util.ArrayList<>(equity);
        facts.addAll(List.of(profit, eps(1000), weighted(10_000_000)));

        Judged off = KrCommonIncome.judge(Q2, row, facts, COMMON_ONLY, null, NO_ACTIONS);
        Judged on = KrCommonIncome.judge(Q2, row, facts, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4);
        // 대조군 — 기말 자본총계가 지배 자본보다 크면(비지배 있음) 대체 앵커가 안 선다.
        java.util.List<Fact> withMinority = new java.util.ArrayList<>(facts);
        withMinority.removeIf(f -> "Equity".equals(f.concept()) && END.equals(f.end()));
        withMinority.add(instant("Equity", END, 650));
        Judged minority = KrCommonIncome.judge(Q2, row, withMinority, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4);

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:no-controlling");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("10000000000");
        assertThat(minority.branch()).isEqualTo("none:no-controlling");
    }

    @Test
    void uAcceptsTheIntervalOnlyWhenTheRegisterRangeIsNarrow() {
        // FY · E 100원 · 지배 NI 10.6억 — 앞 해 명부 1,000만 한 점 증인은 깨진다(6천만 > 0.5S + 5%). NI ÷ (E ± 0.5) = [1,054.7만, 1,065.3만]이
        // 명부 구간 [1,000만(앞 해), 1,045만(그 해)] 위 0.93% 안 · 구간 폭 4.5%(≤ 5%) — 국내 관문 켬 U가 배분 0으로 받는다.
        Period fy = new Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        RowIncome income = rowNi(1_060_000_000L);
        List<Fact> facts = List.of(fact(Source.IFRS, "ProfitLossAttributableToOwnersOfParent", fy, List.of(), 1_060_000_000L),
                fact(Source.IFRS, "BasicEarningsLossPerShare", fy, List.of(ORDINARY), 100));
        Register prior = register(row("보통주", 10_000_000, 0));
        Register narrow = register(row("보통주", 10_450_000, 0));
        // 대조군 — 그 해 명부가 1,200만(구간 폭 20%)이면 넓은 구간이 배분을 감출 수 있어 안 받는다(C+⑷).
        Register wide = register(row("보통주", 12_000_000, 0));

        assertThat(KrCommonIncome.judge(fy, income, facts, prior, narrow, NO_ACTIONS).branch()).as("꺼짐").isEqualTo("none:witness");
        assertThat(KrCommonIncome.judge(fy, income, facts, prior, narrow, NO_ACTIONS, KrCommonIncome.Rules.V4).branch()).isEqualTo("kr-ni");
        assertThat(KrCommonIncome.judge(fy, income, facts, prior, wide, NO_ACTIONS, KrCommonIncome.Rules.V4).branch()).isEqualTo("none:witness");
    }

    private static Fact instant(String concept, LocalDate at, long value) {
        return new Fact(Source.IFRS, concept, null, at, Basis.CONSOLIDATED, List.of(), BigDecimal.valueOf(value));
    }

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
    void aRowWhoseControllingIncomeCameFromTheIdentityAnchorsOnConsolidatedProfit() {
        // 펌텍코리아 꼴 — 그 판에 지배 귀속 개념이 없고 연결 순이익뿐이다. 행 NI가 연결 순이익과 같으면 행은 이 판의 연결 맥락이고, 행 지배 NI는
        // 매퍼가 지배 + 비지배 = 순이익으로 확인한 값이다.
        Fact profit = fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_300_000_000L);
        RowIncome row = new RowIncome(BigDecimal.valueOf(10_000_000_000L), BigDecimal.valueOf(10_300_000_000L));
        Judged j = KrCommonIncome.judge(Q2, row, List.of(profit, eps(1000), weighted(10_000_000)), COMMON_ONLY, null, NO_ACTIONS);
        assertThat(j.branch()).isEqualTo("kr-ni");
        assertThat(j.common()).isEqualByComparingTo("10000000000");

        // 행 NI도 연결 순이익과 갈리면 이 판의 행이 아니다.
        RowIncome other = new RowIncome(BigDecimal.valueOf(10_000_000_000L), BigDecimal.valueOf(10_500_000_000L));
        assertThat(KrCommonIncome.judge(Q2, other, List.of(profit, eps(1000), weighted(10_000_000)), COMMON_ONLY, null, NO_ACTIONS)
                .branch()).isEqualTo("none:anchor");
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

    private static Judged judgeV4(RowIncome row, Register prior, Fact... facts) {
        return KrCommonIncome.judge(Q2, row, List.of(facts), prior, null, NO_ACTIONS, KrCommonIncome.Rules.V4);
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
