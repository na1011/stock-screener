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
    private static final Member PARENT = new Member("ComponentsOfEquityAxis", "EquityAttributableToOwnersOfParentMember", Source.IFRS);
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
    void r1PlusTakesTheLedgerPreferredShareWhenItCloses() {
        // 우선주 100만 · 3개월 W 없음 · 보통주 E 1,000 · 우선주 E 1,050 · 지배 NI 110.5억 — R1(배분 0)은 안 닫히고 NI − 우선 EPS × 명부 우선 유통
        // = 100억이 E × 명부 보통주와 닫힌다(국내 관문 켬 R1+). 값은 행 해석이 누계 갈래 뒤에 고르므로 판정은 no-w에 대체 판정을 단다.
        Fact preferred = fact(Source.DART, "BasicEarningsLossPerSharePreferredStock", Q2, List.of(), 1050);
        Judged off = judge(rowNi(11_050_000_000L), WITH_PREFERRED, owners(11_050_000_000L), eps(1000), preferred);
        Judged on = judgeV4(rowNi(11_050_000_000L), WITH_PREFERRED, owners(11_050_000_000L), eps(1000), preferred);
        // 대조군 — 우선주 E 1,100이면 5,000만 어긋나 보통주 반올림 폭(500만) 밖이다.
        Judged apart = judgeV4(rowNi(11_050_000_000L), WITH_PREFERRED, owners(11_050_000_000L), eps(1000),
                fact(Source.DART, "BasicEarningsLossPerSharePreferredStock", Q2, List.of(), 1100));

        assertThat(off.fallback()).as("꺼짐 — 지금 동작").isNull();
        assertThat(on.branch()).isEqualTo("none:no-w");
        assertThat(on.fallback().branch()).isEqualTo("r1+");
        assertThat(on.fallback().common()).isEqualByComparingTo("10000000000");
        assertThat(apart.fallback()).isNull();
    }

    @Test
    void r1PlusTakesTheOwnHybridFlowAndNeedsItWhenHybridEvidenceExists() {
        // 신종자본 · 지배 열 배당 5억(그 기간) · E 1,000 × 명부 1,000만 = 100억 = 지배 NI 105억 − 5억.
        Fact flow = fact(Source.DART, "DividendToHybridBond", Q2, List.of(PARENT), 500_000_000);
        Judged on = judgeV4(rowNi(10_500_000_000L), COMMON_ONLY, owners(10_500_000_000L), eps(1000), flow);
        // 대조군 — 신종자본 증거(다른 기간 배당)가 있는데 그 기간 지배 열 값이 없으면 우선주 몫만으로 닫혀도 안 받는다.
        Fact elsewhere = fact(Source.DART, "DividendToHybridBond", H1, List.of(PARENT), 500_000_000);
        Fact preferred = fact(Source.DART, "BasicEarningsLossPerSharePreferredStock", Q2, List.of(), 500);
        Judged unknown = judgeV4(rowNi(10_500_000_000L), WITH_PREFERRED, owners(10_500_000_000L), eps(1000), elsewhere, preferred);

        assertThat(on.fallback().branch()).isEqualTo("r1+");
        assertThat(on.fallback().common()).isEqualByComparingTo("10000000000");
        assertThat(unknown.fallback()).isNull();
    }

    @Test
    void r1PlusBuildsTheQuarterHybridFlowFromTwoYearToDateFilings() {
        // 반기 판은 지배 열 신종자본 배당을 누계(1~6월 8억)로만 싣는다 — 3개월 몫 = 8억 − 1분기 판 누계 3억 = 5억.
        Fact now = fact(Source.DART, "DividendToHybridBond", H1, List.of(PARENT), 800_000_000);
        Fact before = fact(Source.DART, "DividendToHybridBond", quarter(2026, 1), List.of(PARENT), 300_000_000);
        KrCommonIncome.NcContext nc = new KrCommonIncome.NcContext(H1.start(), quarter(2026, 1).end(), List.of(before));
        Judged on = KrCommonIncome.judge(Q2, rowNi(10_500_000_000L), List.of(owners(10_500_000_000L), eps(1000), now), COMMON_ONLY, null,
                NO_ACTIONS, KrCommonIncome.Rules.V4, nc);
        Judged alone = KrCommonIncome.judge(Q2, rowNi(10_500_000_000L), List.of(owners(10_500_000_000L), eps(1000), now), COMMON_ONLY, null,
                NO_ACTIONS, KrCommonIncome.Rules.V4, null);

        assertThat(on.fallback().common()).isEqualByComparingTo("10000000000");
        assertThat(alone.fallback()).as("직전 판 누계가 없으면 3개월 몫을 모른다").isNull();
    }

    @Test
    void an4AnchorsAQuarterRowOnTheEquityColumnDifference() {
        // 반기 행 — 매퍼가 표준 지배 줄(3개월 120억 · 계속영업 몫)을 반박하고 100억을 골랐다(K19). 그 판 자본변동표 지배 열 누계 175억 − 1분기 판
        // 누계 75억 = 100억 = 행 지배 NI · 연결 순이익 누계 차 180억 − 80억 = 3개월 100억(소급 가드).
        List<Fact> facts = List.of(owners(12_000_000_000L), fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_000_000_000L),
                fact(Source.IFRS, "ProfitLoss", H1, List.of(), 18_000_000_000L), fact(Source.IFRS, "ProfitLoss", H1, List.of(PARENT), 17_500_000_000L));
        Period first = quarter(2026, 1);
        KrCommonIncome.NcContext nc = new KrCommonIncome.NcContext(H1.start(), first.end(), List.of(
                fact(Source.IFRS, "ProfitLoss", first, List.of(), 8_000_000_000L), fact(Source.IFRS, "ProfitLoss", first, List.of(PARENT), 7_500_000_000L)));
        Judged on = KrCommonIncome.judge(Q2, rowNi(10_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4, nc);
        Judged off = KrCommonIncome.judge(Q2, rowNi(10_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS,
                KrCommonIncome.Rules.V4.without(KrCommonIncome.Rule.AN4), nc);
        // 대조군 — 누계 차가 행 지배 NI와 다르면(1분기 판 지배 열 80억 → 95억) 앵커가 안 선다.
        KrCommonIncome.NcContext apart = new KrCommonIncome.NcContext(H1.start(), first.end(), List.of(
                fact(Source.IFRS, "ProfitLoss", first, List.of(), 8_000_000_000L), fact(Source.IFRS, "ProfitLoss", first, List.of(PARENT), 8_000_000_000L)));
        Judged differs = KrCommonIncome.judge(Q2, rowNi(10_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4, apart);

        assertThat(off.branch()).isEqualTo("none:anchor");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("10000000000");
        assertThat(differs.branch()).isEqualTo("none:anchor");
    }

    @Test
    void an4AnchorsOnProfitLessMinorityUnlessTheEquityColumnSaysOtherwise() {
        // 한전KPS 2020 1분기 꼴 — 그 판 표준 지배 사실(120억)이 비지배를 안 뺐다. 연결 순이익 105억 − 비지배 5억 = 100억 = 행 지배 NI(매퍼가 고른 값).
        List<Fact> facts = List.of(owners(12_000_000_000L), fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_500_000_000L),
                fact(Source.IFRS, "ProfitLossAttributableToNoncontrollingInterests", Q2, List.of(), 500_000_000L));
        Judged on = KrCommonIncome.judge(Q2, rowNi(10_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4);
        // 대조군 — 같은 기간 자본변동표 지배 열이 98억을 말하면(손익 · 자본변동표가 각자 닫히며 몫을 달리 나눈 판 · 043260 FY2024) 안 받는다.
        List<Fact> twisted = new ArrayList<>(facts);
        twisted.add(fact(Source.IFRS, "ProfitLoss", Q2, List.of(PARENT), 9_800_000_000L));
        Judged apart = KrCommonIncome.judge(Q2, rowNi(10_000_000_000L), twisted, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4);

        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("10000000000");
        assertThat(apart.branch()).isEqualTo("none:anchor");
    }

    @Test
    void swWitnessesTheIncomeOnTheShareTimeline() {
        // 지분상품 없음 · W 없음 · 기간 중 증자로 앞 해 명부(1,000만)가 W와 10% 갈려 지금 증인이 실패한다. 시간표 구간 [1,095만, 1,100만]이
        // NI 110억 ÷ E 1,000과 닫힌다 — 배분 0 · 시간표 판 접수일부터 보인다(국내 관문 켬 SW).
        List<Fact> facts = List.of(owners(11_000_000_000L), eps(1000));
        KrShareTimeline.Window narrow = new KrShareTimeline.Window(new BigDecimal("10950000"), new BigDecimal("11000000"), LocalDate.of(2026, 9, 1));
        Judged without = KrCommonIncome.judge(Q2, rowNi(11_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4, null,
                List.of(), null);
        Judged on = KrCommonIncome.judge(Q2, rowNi(11_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4, null,
                List.of(), narrow);
        // 대조군 — 구간 폭이 1%를 넘으면(자기주식 취득 · 처분 일자를 몰라 넓다) 숨은 배분을 가릴 수 없어 안 받는다.
        KrShareTimeline.Window wide = new KrShareTimeline.Window(new BigDecimal("10600000"), new BigDecimal("11000000"), LocalDate.of(2026, 9, 1));
        Judged loose = KrCommonIncome.judge(Q2, rowNi(11_000_000_000L), facts, COMMON_ONLY, null, NO_ACTIONS, KrCommonIncome.Rules.V4, null,
                List.of(), wide);

        assertThat(without.branch()).isEqualTo("none:witness");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("11000000000");
        assertThat(on.laterReceipt()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(loose.branch()).isEqualTo("none:witness");
    }

    @Test
    void payDeclaresTheDenominatorApartFromTheNumerator() {
        // 지분상품 없음 · 총 E 없음 — 분자는 판정 실패(no-eps)여도 분모는 지배 NI다(국내 관문 켬 PAY · 지금은 분모까지 비운다).
        Judged noEpsOff = judge(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L));
        Judged noEps = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L));
        // 지배기업 신종자본 · 그 기간 지배 열 배당 5억 — 분모 = 지배 NI − 5억.
        Judged hybrid = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L),
                fact(Source.DART, "DividendToHybridBond", Q2, List.of(PARENT), 500_000_000));
        // 대조군 — 신종자본 증거는 있는데 그 기간 흐름이 없으면 분모를 지어내지 않는다.
        Judged unknown = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L),
                fact(Source.DART, "DividendToHybridBond", H1, List.of(PARENT), 500_000_000));

        assertThat(noEpsOff.payoutBase()).as("꺼짐 — 지금 동작").isNull();
        assertThat(noEps.branch()).isEqualTo("none:no-eps");
        assertThat(noEps.payoutBase()).isEqualByComparingTo("10000000000");
        assertThat(hybrid.payoutBase()).isEqualByComparingTo("9500000000");
        assertThat(hybrid.payoutFollowsCommon()).as("지배 NI − 흐름은 보통주 몫의 가시일을 안 탄다").isFalse();
        assertThat(unknown.payoutBase()).isNull();
    }

    @Test
    void hyownCountsOnlyTheParentsOwnHybridCapital() {
        // 별도 재무제표가 있는 연결 판 — 연결 축 없는 신종자본 잔액(자회사 것일 수 있다)만 있으면 지분상품 없음 · 비지배 구성원 신종자본도 안 센다.
        Fact consolidated = fact(Source.DART, "HybridBonds", Q2, List.of(), 300_000_000_000L);
        Fact separateRow = new Fact(Source.IFRS, "ProfitLoss", Q2.start(), Q2.end(), Basis.SEPARATE, List.of(), BigDecimal.valueOf(1));
        Fact minority = fact(Source.DART, "DividendToHybridBond", Q2,
                List.of(new Member("ComponentsOfEquityAxis", "NoncontrollingInterestsMember", Source.IFRS)), 100_000_000);
        Judged subsidiary = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(10_000_000),
                consolidated, separateRow, minority);
        // 별도 기저의 지배기업 신종자본 — 앵커가 연결이어도 지분상품이다(엠케이전자 2024 FY).
        Fact own = new Fact(Source.DART, "DividendsPaidToHybridBond", Q2.start(), Q2.end(), Basis.SEPARATE, List.of(), BigDecimal.valueOf(-1_000_000_000L));
        Judged parent = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(10_000_000), own);
        Judged before = judge(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(10_000_000), consolidated,
                separateRow, minority);

        assertThat(before.branch()).as("꺼짐 — 연결 잔액 · 철자 틀린 비지배 거르기로 지분상품 있음").isEqualTo("exw");
        assertThat(subsidiary.branch()).isEqualTo("kr-ni");
        assertThat(parent.branch()).isEqualTo("exw");
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
    void hyIgnoresHybridEvidenceLeftOnlyInThePriorColumnAfterRepayment() {
        // 신종자본을 앞 해에 갚아 그 판의 앞 열에만 상환 줄이 남았다(포스코인터내셔널 2025 FY 꼴) — 당기 열에 증거가 없으면 지분상품 있음이 아니다(국내 관문 켬 HY).
        Fact repaid = fact(Source.DART, "RepaymentOfHybridBond", new Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)), List.of(),
                140_000_000_000L);
        Judged off = judge(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(10_000_000), repaid);
        Judged on = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(10_000_000), repaid);
        // 대조군 — 당기 열에 신종자본 이자가 있으면 그대로 지분상품 있음이다.
        Judged current = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(10_000_000), repaid,
                fact(Source.DART, "InterestExpenseForHybridBond", Q2, List.of(), 1_000_000_000L));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("exw");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.payoutBranch()).isEqualTo("ni");
        assertThat(current.branch()).isEqualTo("exw");
    }

    @Test
    void p1JudgesTheMixedPreferenceByOutstandingShares() {
        // 보통주 1,000만 · 우선주 100만 중 90만이 자기주식 — 발행 기준 보통주 밖 9%지만 유통 기준 1%다. W 1,010만은 유통 합계와 같아도
        // 우선주를 섞은 것이 아니다(국내 관문 켬 P1).
        Register treasuryPreferred = register(row("보통주", 10_000_000, 0), row("우선주", 1_000_000, 900_000));
        Judged off = judge(rowNi(10_000_000_000L), treasuryPreferred, owners(10_000_000_000L), eps(990), weighted(10_100_000));
        Judged on = judgeV4(rowNi(10_000_000_000L), treasuryPreferred, owners(10_000_000_000L), eps(990), weighted(10_100_000));
        // 대조군 — 우선주가 유통 중이면(보통주 밖 유통 9%) 그대로 섞음이다.
        Register circulating = register(row("보통주", 10_000_000, 0), row("우선주", 1_000_000, 0));
        Judged mixed = judgeV4(rowNi(10_000_000_000L), circulating, owners(10_000_000_000L), eps(990), weighted(11_000_000));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:w-includes-pref");
        assertThat(on.branch()).isEqualTo("exw");
        assertThat(on.common()).isEqualByComparingTo("9999000000");
        assertThat(mixed.branch()).isEqualTo("none:w-includes-pref");
    }

    @Test
    void rbTakesTheWeightedSharesReportedOnASingleOrdinaryClassMember() {
        // 표준 구성원 W가 없고 ClassesOfOrdinarySharesAxis 회사 구성원 하나에만 W 1,000만을 적은 판(성우하이텍 2024 Q2 꼴 · 국내 관문 켬 RB).
        Member entityClass = new Member("ClassesOfOrdinarySharesAxis", "OrdinarySharesMemberOfEarningsPerShareTableOfMember", Source.ENTITY);
        Fact axisWeighted = fact(Source.IFRS, "WeightedAverageShares", Q2, List.of(entityClass), 10_000_000);
        Judged off = judge(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(990), axisWeighted);
        Judged on = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(990), axisWeighted);
        // 대조군 — 그 축에 구성원이 둘이면(종류별 W) 어느 것이 보통주인지 모른다.
        Fact other = fact(Source.IFRS, "WeightedAverageShares", Q2,
                List.of(new Member("ClassesOfOrdinarySharesAxis", "SecondClassMember", Source.ENTITY)), 1_000_000);
        Judged twoMembers = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(990), axisWeighted, other);

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:no-w");
        assertThat(on.branch()).isEqualTo("exw");
        assertThat(on.common()).isEqualByComparingTo("9900000000");
        assertThat(twoMembers.branch()).isEqualTo("none:no-w");
    }

    @Test
    void reg2FoldsARegisterPrintedTwiceAndRefusesTwoDifferentCopies() {
        // 같은 표를 두 번 실은 명부(024850 2023 꼴) — 접으면 보통주 한 종류다 · 안 접으면 보통주 줄 둘이 두 종류로 세어진다(국내 관문 켬 REG2).
        List<ShareRow> twice = List.of(row("보통주", 66_507_231, 7_024), row("보통주", 66_507_231, 7_024), row("합계", 66_507_231, 7_024),
                row("합계", 66_507_231, 7_024));
        // 두 벌이 다르면(263750 2017 꼴) 어느 벌이 기말 명부인지 모른다.
        List<ShareRow> differ = List.of(row("보통주", 12_066_300, 848_760), row("보통주", 10_266_300, 848_760), row("합계", 10_266_300, 848_760),
                row("합계", 12_066_300, 848_760));

        assertThat(Register.of(REGISTER_DAY, twice).multiClass()).as("접기 전 — 지금 동작").isTrue();
        assertThat(Register.of(REGISTER_DAY, KrCommonIncomeDeriver.folded(twice)).multiClass()).isFalse();
        assertThat(Register.of(REGISTER_DAY, KrCommonIncomeDeriver.folded(twice)).commonOutstanding()).isEqualByComparingTo("66500207");
        assertThat(KrCommonIncomeDeriver.folded(differ)).isNull();
    }

    @Test
    void u1TrustsTheLedgerWeightedSharesWhenTheEpsIdentityClosesWithoutInstruments() {
        // 지분상품 없음 · W 2,500만이 명부 1,000만의 2.5배(단위 점검에 걸림) · 그래도 E 400 × W = 지배 NI 100억(국내 관문 켬 U1).
        Judged off = judge(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(400), weighted(25_000_000));
        Judged on = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(400), weighted(25_000_000));
        // W 0은 W가 없는 것 — 명부 증인(E × S)으로 간다.
        Judged zero = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, owners(10_000_000_000L), eps(1000), weighted(0));
        // 대조군 — 지분상품이 있으면 그대로 단위 오류다.
        Judged instruments = judgeV4(rowNi(10_000_000_000L), WITH_PREFERRED, owners(10_000_000_000L), eps(400), weighted(25_000_000));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:w-unit");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(zero.branch()).isEqualTo("kr-ni");
        assertThat(instruments.branch()).isEqualTo("none:w-unit");
    }

    @Test
    void an3AnchorsOnTheOwnersWhenTheRowCarriesTheConsolidatedProfit() {
        // 행 지배 NI 100억이 연결 순이익과 같다(매퍼가 연결 순이익을 지배 칸에 둔 꼴) · 표준 지배 90억 + 비지배 10억 = 100억(국내 관문 켬 AN3).
        Fact profit = fact(Source.IFRS, "ProfitLoss", Q2, List.of(), 10_000_000_000L);
        Fact minority = fact(Source.IFRS, "ProfitLossAttributableToNoncontrollingInterests", Q2, List.of(), 1_000_000_000L);
        Judged off = judge(rowNi(10_000_000_000L), COMMON_ONLY, profit, owners(9_000_000_000L), minority, eps(900), weighted(10_000_000));
        Judged on = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, profit, owners(9_000_000_000L), minority, eps(900), weighted(10_000_000));
        // 대조군 — 지배 + 비지배가 행 값과 안 맞으면 앵커가 안 선다.
        Judged apart = judgeV4(rowNi(10_000_000_000L), COMMON_ONLY, profit, owners(8_000_000_000L), minority, eps(900), weighted(10_000_000));

        assertThat(off.branch()).as("꺼짐 — 지금 동작").isEqualTo("none:anchor");
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.common()).isEqualByComparingTo("9000000000");
        assertThat(apart.branch()).isEqualTo("none:anchor");
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
    void cmpLeavesARowThatTheFilingsOwnCumulativeDecides() {
        // 신종자본이 있는 반기 판 — 3개월 W가 없지만 반기 누계 E 270 × W 1,000만 − 1분기 9억 = 18억이 그 판 재료로 선다. 뒤 판이 2분기 W를 실어도
        // 비교열 W(CMP)는 그 판으로 값이 안 설 때만이다 — 누계 값을 지키고 가시일도 안 밀린다.
        Period q1 = quarter(2025, 1);
        Period q2 = quarter(2025, 2);
        Period h1 = new Period(q1.start(), q2.end());
        Row first = new Row(1, 2025, "Q1", q1.start(), q1.end(), LocalDate.of(2025, 5, 15), BigDecimal.valueOf(1_000_000_000L), null,
                exwFacts(q1, 1_000_000_000L, 90));
        Row half = new Row(2, 2025, "Q2", q2.start(), q2.end(), LocalDate.of(2025, 8, 14), BigDecimal.valueOf(2_000_000_000L), null,
                List.of(fact(Source.IFRS, "ProfitLossAttributableToOwnersOfParent", q2, List.of(), 2_000_000_000L),
                        fact(Source.IFRS, "BasicEarningsLossPerShare", q2, List.of(ORDINARY), 180),
                        fact(Source.IFRS, "BasicEarningsLossPerShare", h1, List.of(ORDINARY), 270),
                        fact(Source.IFRS, "WeightedAverageShares", h1, List.of(ORDINARY), 10_000_000),
                        fact(Source.DART, "HybridBonds", new Period(null, q2.end()), List.of(), 1_000_000_000L)));
        KrCommonIncome.LaterFiling later = new KrCommonIncome.LaterFiling("20251114000001", LocalDate.of(2025, 11, 14),
                List.of(fact(Source.IFRS, "BasicEarningsLossPerShare", q2, List.of(ORDINARY), 180),
                        fact(Source.IFRS, "WeightedAverageShares", q2, List.of(ORDINARY), 10_100_000)));

        Resolved q2Resolved = byId(KrCommonIncomeRows.resolve(List.of(first, half), Map.of(2024, COMMON_ONLY), NO_ACTIONS,
                KrCommonEquity.Issuer.of(List.of()), KrCommonIncome.Rules.V4, row -> row.id() == 2 ? List.of(later) : List.of())).get(2L);

        assertThat(q2Resolved.branch()).isEqualTo("cum");
        assertThat(q2Resolved.common()).isEqualByComparingTo("1800000000");
        assertThat(q2Resolved.visibleFrom()).isNull();
    }

    @Test
    void pitUsesTheSameYearRegisterOnlyWithoutAPriorOneAndShowsItFromTheAnnualReport() {
        // 1분기 행 · W 없음 · 지분상품 없음 — 증인은 명부(E 1,000 × 1,000만 = 지배 NI 100억). 앞 해 명부가 없고 그 해 명부만 있다(국내 관문 켬 PIT).
        Period q1 = quarter(2025, 1);
        Row first = new Row(1, 2025, "Q1", q1.start(), q1.end(), LocalDate.of(2025, 5, 15), BigDecimal.valueOf(10_000_000_000L), null,
                List.of(fact(Source.IFRS, "ProfitLossAttributableToOwnersOfParent", q1, List.of(), 10_000_000_000L),
                        fact(Source.IFRS, "BasicEarningsLossPerShare", q1, List.of(ORDINARY), 1000)));
        Period fy = new Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        Row annual = new Row(5, 2025, "FY", fy.start(), fy.end(), LocalDate.of(2026, 3, 10), BigDecimal.valueOf(40_000_000_000L), null,
                null);

        Resolved off = byId(KrCommonIncomeRows.resolve(List.of(first, annual), Map.of(2025, COMMON_ONLY), NO_ACTIONS)).get(1L);
        Resolved on = byId(KrCommonIncomeRows.resolve(List.of(first, annual), Map.of(2025, COMMON_ONLY), NO_ACTIONS,
                KrCommonEquity.Issuer.of(List.of()), KrCommonIncome.Rules.V4)).get(1L);
        // 그 해 사업보고서가 아직 없으면 안 보인다 · 앞 해 명부가 있으면 그 해 명부를 안 쓴다(그 행 공시일부터).
        Resolved notYet = byId(KrCommonIncomeRows.resolve(List.of(first), Map.of(2025, COMMON_ONLY), NO_ACTIONS,
                KrCommonEquity.Issuer.of(List.of()), KrCommonIncome.Rules.V4)).get(1L);
        Resolved prior = byId(KrCommonIncomeRows.resolve(List.of(first, annual), Map.of(2024, COMMON_ONLY, 2025, COMMON_ONLY), NO_ACTIONS,
                KrCommonEquity.Issuer.of(List.of()), KrCommonIncome.Rules.V4)).get(1L);

        assertThat(off.branch()).isEqualTo("kr-ni");
        assertThat(off.visibleFrom()).as("꺼짐 — 지금 동작(가시일 없음)").isNull();
        assertThat(on.branch()).isEqualTo("kr-ni");
        assertThat(on.visibleFrom()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(notYet.visibleFrom()).isEqualTo(KrCommonIncomeRows.NEVER);
        assertThat(prior.branch()).isEqualTo("kr-ni");
        assertThat(prior.visibleFrom()).isNull();
    }


    @Test
    void qregTakesTheQuarterRegisterInsteadOfTheYearEndOneForAFirstYearQuarter() {
        // 첫해 1분기 — 앞 해 명부가 없다. 그 해 명부(연말 · 미래)는 보통주 1,000만 · 우선주 15만(몫 0.98522)이고, 그 판 분기 명부는 보통주 990만 ·
        // 우선주 10만(0.99)이다(국내 관문 켬 QREG — 둘 다 보통주 밖 2% 이하라 참가 증거 없이 몫을 낸다).
        Period q1 = quarter(2025, 1);
        Row first = new Row(1, 2025, "Q1", q1.start(), q1.end(), LocalDate.of(2025, 5, 15), BigDecimal.valueOf(10_000_000_000L), null,
                List.of(fact(Source.IFRS, "ProfitLossAttributableToOwnersOfParent", q1, List.of(), 10_000_000_000L)));
        Register yearEnd = register(row("보통주", 10_000_000, 0), row("우선주", 150_000, 0));
        KrShareTimeline quarterTable = new KrShareTimeline(List.of(new KrXbrlStore.ShareMaterial(2025, "11013", "stockTotqySttus",
                "20250515000001", List.of(table("보통주", "9,900,000"), table("우선주", "100,000"), table("합계", "10,000,000"),
                table("비고", "분할로 인한 단주 취득")))), Map.of(2025, yearEnd));

        Resolved off = byId(KrCommonIncomeRows.resolve(List.of(first), Map.of(2025, yearEnd), NO_ACTIONS, KrCommonEquity.Issuer.of(List.of()),
                KrCommonIncome.Rules.V4.without(KrCommonIncome.Rule.QREG), KrCommonIncomeRows.Later.NONE, quarterTable)).get(1L);
        Resolved on = byId(KrCommonIncomeRows.resolve(List.of(first), Map.of(2025, yearEnd), NO_ACTIONS, KrCommonEquity.Issuer.of(List.of()),
                KrCommonIncome.Rules.V4, KrCommonIncomeRows.Later.NONE, quarterTable)).get(1L);
        // 대조군 — 그 시점 분기 명부가 없으면(분기 표가 「-」인 회사) 연말 명부가 여러 종류라 몫을 모른다.
        Resolved unknown = byId(KrCommonIncomeRows.resolve(List.of(first), Map.of(2025, yearEnd), NO_ACTIONS, KrCommonEquity.Issuer.of(List.of()),
                KrCommonIncome.Rules.V4, KrCommonIncomeRows.Later.NONE, new KrShareTimeline(List.of(), Map.of(2025, yearEnd)))).get(1L);

        assertThat(off.equity().fraction()).as("끔 — 연말 명부").isEqualByComparingTo("0.985221674876847291");
        assertThat(on.equity().fraction()).isEqualByComparingTo("0.99");
        assertThat(unknown.equity().fraction()).isNull();
    }

    private static tools.jackson.databind.JsonNode table(String label, String issued) {
        return new tools.jackson.databind.ObjectMapper().readTree(
                "{\"se\":\"%s\",\"istc_totqy\":\"%s\",\"tesstk_co\":\"-\"}".formatted(label, issued));
    }

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
