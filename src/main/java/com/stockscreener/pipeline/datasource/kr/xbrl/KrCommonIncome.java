package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Basis;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Fact;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Member;
import com.stockscreener.pipeline.datasource.kr.xbrl.DartXbrlFactReader.Source;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 국내 재무 행 하나(판 하나 · 그 기간)의 <b>보통주 귀속 이익</b>과 <b>배당성향 분모</b>를 그 판의 XBRL 재료 · 주식총수 명부 · 기업행위
 * 배수에서 판정한다(ADR-0192 §규칙 「판정」). 외부 호출이 없는 순수 함수다.
 *
 * <p>규칙의 정본은 ADR-0192이고, 0콜 기준 구현이 탐색 브랜치 {@code kr5/plan9h.py}다(같은 346 단면에서 갈래 수가 같아야 한다 —
 * {@code DartCommonIncomeSweepTest}).
 */
public final class KrCommonIncome {

    /** 판정 엔진 판. 저장 행({@code financial_common_income.engine_version})이 이 값과 다르면 다음 유도가 다시 붙인다. */
    public static final int ENGINE_VERSION = 3;
    /** 엔진 4판(국내 관문 켬 · {@link Rules#V4}) — 관문 켬 유도가 이 판으로 적는다. */
    public static final int ENGINE_VERSION_V4 = 4;

    /** 보통주 EPS · 가중평균 사실이 달 수 있는 표준 구성원(연결 축 밖). 회사 구성원은 이름이 같아도 받지 않는다. */
    static final Set<String> COMMON_MEMBERS = Set.of(
            "OrdinarySharesMember", "ReportedAmountMember", "EquityAttributableToOwnersOfParentMember", "ParentMember");

    private static final BigDecimal HALF = new BigDecimal("0.5");
    /** 증인의 주식수 변화 여유 — 지분상품 없는 판의 E × 기말 주식수가 지배 NI와 이만큼 안이어야 한다(ADR-0192). */
    private static final BigDecimal WITNESS_SLACK = new BigDecimal("0.05");
    /** W ÷ 기준 보통주 수의 큰 오류 경계(단위 · 주식-일) — 이 밖이면 그 W를 안 쓴다. */
    private static final double W_RATIO_MIN = 0.5;
    private static final double W_RATIO_MAX = 2.0;
    /** 구형우선주를 섞은 W — 보통주 밖 포함 유통과 이만큼 안이고 보통주 밖 비중이 이만큼 넘으면. */
    private static final double MIXED_PREF_BAND = 0.03;

    private static final String EPS = "BasicEarningsLossPerShare";
    private static final String EPS_CONTINUING = "BasicEarningsLossPerShareFromContinuingOperations";
    /** 보통주 희석 EPS — 재료 표에는 관문 켬의 판독기 판에만 있다({@link DartXbrlFactReader#KR_GATE_MATERIAL}). */
    private static final String EPS_DILUTED = "DilutedEarningsLossPerShare";
    private static final String WEIGHTED = "WeightedAverageShares";
    private static final String DIRECT = "ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity";
    private static final String OWNERS = "ProfitLossAttributableToOwnersOfParent";
    private static final String PROFIT = "ProfitLoss";
    private static final String NONCONTROLLING = "ProfitLossAttributableToNoncontrollingInterests";
    private static final String OUTSTANDING = "NumberOfSharesOutstanding";
    static final String PREF_EPS_DART = "BasicEarningsLossPerSharePreferredStock";
    /** {@link Rule#R1P}의 H — 지배 열의 신종자본 배당 · 이자. */
    private static final Set<String> HYBRID_FLOWS = Set.of("DividendToHybridBond", "InterestExpenseForHybridBond");
    private static final String OWNERS_COLUMN = "EquityAttributableToOwnersOfParentMember";
    static final String PREF_EPS_IFRS = "BasicEarningsLossPerInstrumentParticipatingEquityInstrumentsOtherThanOrdinaryShares";
    private static final String PREF_WEIGHTED =
            "WeightedAverageNumberOfInstrumentsUsedInCalculatingBasicEarningsLossPerInstrumentParticipatingEquityInstrumentsOtherThanOrdinaryShares";
    private static final String CLASS_AXIS = "ClassesOfShareCapitalAxis";
    /** 우선주 종류 열쇠를 정할 때 지나칠 수 있는 축과 그 구성원(표준 「공시금액」 · 지배주주 지분). 그 밖 축이 붙은 사실은 안 받는다. */
    private static final Map<String, String> PASS_THROUGH_AXES = Map.of(
            "CarryingAmountAccumulatedDepreciationAmortisationAndImpairmentAndGrossCarryingAmountAxis", "ReportedAmountMember",
            "ComponentsOfEquityAxis", "EquityAttributableToOwnersOfParentMember");
    private static final String AXISLESS_CLASS = "*";

    private KrCommonIncome() {
    }

    /** 판정할 기간. */
    public record Period(LocalDate start, LocalDate end) {
    }

    /**
     * 엔진 4판의 규칙(국내 관문 켬 · 탐색 {@code KR-BLANKS-PLAN-1106.md} 「C1~C16」 · 0콜 기준 구현 {@code engine4.py}의 같은 이름 플래그). 꺼짐
     * ({@link #NONE})은 3판 그대로다. 규칙마다 기준 구현과 같은 재료에서 갈래 · 값이 행마다 같은지를 대조하고 들인다.
     */
    public enum Rule {
        /** 지분상품 있음 · W 없음 · E 하나 · |지배 NI − E × S| ≤ 0.5S면 배분 0(S = 기준 명부 보통주 유통 × 기업행위 배수). */
        R1,
        /**
         * 행 지배 NI가 빈 연결 행(no-controlling)의 대체 앵커 — NC-a: 같은 판 누계 지배 귀속 − 직전 판 누계 지배 귀속(연결 순이익 누계 차가 3개월
         * 순이익과 닫힐 때만 · Q2 · Q3) · NC-b: 기초 · 기말 자본총계 = 지배 자본이고 0 아닌 비지배 손익이 없으면 연결 순이익이 지배 NI다.
         */
        NC,
        /**
         * 증인 넓힘 — 지금 증인(E × S가 지배 NI와 0.5S + 5% 안)이 깨져도, NI ÷ E 구간(E ± 0.5)이 명부 구간(앞 해 · FY는 그 해까지) ±1% 안이면 배분
         * 0이다(기간 가중평균을 앞 해 말 명부 한 점으로 대신해 증자 · 자사주 해에 멀쩡한 행이 떨어졌다 · 계획 C 「none:witness」). 명부 구간 폭이 증인
         * 여유(5%)를 넘으면 안 받는다(C+⑷ — 한화오션 2023 FY 대규모 증자 · 17.5% 배분을 받았다).
         */
        U,
        /**
         * 상충 해소 — E나 W 후보가 여럿이면 |지배 NI − E × W| ≤ 0.5W로 닫히는 짝을 모아, 그 짝들의 E가 한 값(1원 미만 차이는 같은 값)일 때 잔차가
         * 가장 작은 짝으로 판정을 잇는다(계획 C 「none:conflict」). 지분상품 있는 행은 진짜 짝이 원래 안 닫혀(지분상품 몫만큼 E × W가 작다) 닫히는 짝이
         * 미끼일 수 있다 — 그 행은 W가 하나이고 버리는 E가 모두 고른 E보다 작으며 같은 기간 희석 EPS로도 실렸을 때만 받는다(C+⑵ · 파마리서치
         * 2025 FY · 2026 Q1이 그 꼴이고, HMM · 피엔티 · HD현대 꼴은 상충으로 남는다).
         */
        C1,
        /**
         * 지분상품 있음 · 총 E 없음 · 계속영업 E 하나 · W 하나면 계속영업 E × W(기저 C · 계획 C 「none:no-eps」). W 단위 점검은 다른 E × W 갈래와
         * 같다. 총 E가 없어 중단 포함 보통주 몫을 모르므로 신종자본증권이 있으면 배당성향 분모는 비운다.
         */
        E2,
        /**
         * 총 E 부호 거르기 — E가 하나면 E > 0인데 지배 NI < 0일 때만 버리고(신종자본 · 우선 배분은 보통주 몫을 줄이므로 NI > 0 · E < 0은 정당하다 ·
         * 롯데지주 2026 Q1), 여럿이면 지배 NI와 부호가 같은 것만 남긴다(두산 2025 FY ±4,230 · 같은 것이 없으면 그대로). 0은 안 거른다.
         * 계속영업 E · 누계 E에는 안 건다 — 맞댈 지배 NI가 같은 기저가 아니다.
         */
        SIGN,
        /**
         * 갚은 신종자본 — 신종자본 증거가 그 판의 당기 열(사실 끝이 사업연도 시작 뒤 · 또는 기간 끝 시점)에 하나도 없고 앞 열에 상환({@code Repayment})
         * 개념이 있으면 신종자본 증거로 안 센다(전년 비교열에만 남은 상환 줄이 지분상품 있음을 만들어 멀쩡한 행이 E × W로 갔다 · 계획 HY-b).
         */
        HY,
        /**
         * 구형우선주 섞음({@code w-includes-pref}) 판정의 보통주 밖 비중을 유통 기준으로 — 발행 기준으로 3%를 넘어도 우선주 대부분이 자기주식이면
         * (유통 기준 3% 이하) W가 발행 합계와 비슷해도 섞은 것이 아니다(계획 P1).
         */
        P1,
        /**
         * 축 붙은 W — 표준 구성원 W가 없을 때 {@code ClassesOfOrdinarySharesAxis} 구성원 하나뿐인 W(보통주 한 종류를 축으로 적은 판)와
         * {@code ContinuingAndDiscontinuedOperationsAxis} 두 구성원 값이 같은 W(계속 · 중단 열에 같은 W를 적은 판)를 후보로 쓴다(계획 RB).
         */
        RB,
        /**
         * 「합계」 줄이 둘 이상인 명부 — 같은 표를 두 번 실은 명부다. 라벨마다 두 벌이 같으면 하나로 접어 읽고(지금은 보통주 줄 둘을 두 종류로 세어
         * 우선주 있음을 거짓으로 만든다 · 024850 2023 등 넷), 한 줄이라도 다르면 어느 벌이 기말 명부인지 몰라 못 읽음이다(263750 2017 ·
         * 1,206만 대 1,026만). 계획 REG2의 「전부 못 읽음」은 같은 두 벌까지 버려 앞 해 명부를 잃는다. 적용은 명부를 짓는
         * {@link KrCommonIncomeDeriver}가 한다.
         */
        REG2,
        /**
         * 지분상품 없는 행의 W 단위 — W가 단위 점검({@code w-unit})에 걸려도 E × W가 지배 NI와 0.5W 안에서 닫히면 배분 0이다(원장 W가 맞고 명부
         * 쪽이 낡은 해 · 계획 U1). W ≤ 0은 W가 없는 것으로 보고 증인 갈래로 간다.
         */
        U1,
        /**
         * 행 지배 NI가 연결 순이익과 같은 행(매퍼가 연결 순이익을 지배 칸에 둔 꼴)의 대체 앵커 — 표준 지배 귀속 o와 표준 비지배 귀속 n이 하나씩이고
         * o + n이 행 지배 NI와 같으면 앵커는 o다(계획 AN3).
         */
        AN3,
        /**
         * 시점 재료(PIT-a) — Q1~Q3 행은 그 해 명부(그 해 사업보고서에 실린다 · 분기 공시 때는 아직 없다)를 안 쓴다. 앞 해 명부가 없을 때만 그 해
         * 명부를 쓰고, 그 행의 보통주 몫은 그 해 사업보고서 공시일부터 보인다(없으면 안 보인다). 적용은 행 해석({@link KrCommonIncomeRows})이 한다.
         */
        PIT,
        /**
         * 비교열 W — 그 판에 W가 없고 E가 하나면, 뒤에 접수된 판 중 같은 기간 W가 하나인 첫 판의 W를 쓴다(그 판의 같은 기간 E 집합이 이 판과 같을
         * 때만 · W 단위 점검에 걸리면 안 받는다). 그 판 재료로 값이 안 설 때만 쓰고(행 해석이 고른다), 그 행의 보통주 몫은 뒤 판 접수일부터 보인다.
         */
        CMP,
        /**
         * R1 넓힘 — 지분상품 있음 · W 없음 · E 하나 · R1(배분 0)이 안 닫히면, 원장이 밝힌 비보통 몫 후보 a ∈ {H, P, H + P} 중 |지배 NI − a − E × S|
         * ≤ 0.5S로 닫히는 것이 값으로 하나일 때 보통주 몫 = 지배 NI − a(갈래 {@code r1+}). H = 같은 판 지배 열의 신종자본 배당 · 이자 그 기간 값 하나
         * (반기 · 3분기는 원장이 누계로만 주므로 그 판 누계 − 직전 판 누계) · P = 축 없는 우선주 EPS 그 기간 값 하나 × 기준 명부 우선 유통(종류가 둘일
         * 때). 신종자본 증거가 있는데 H가 안 서면 P만으로는 안 받는다. 반기 · 3분기는 누계 갈래로 안 설 때만 쓴다(행 해석이 고른다 · 계획 K3-a —
         * 원장 W가 있는 행에 같은 산수를 태워 148/148이 E × W와 0.5W 안이다. 닫힘 검사 없이 H만 빼면 한 종류 대조군 90 중 15가 갈린다).
         */
        R1P
    }

    /** {@link Rule#CMP}의 재료 — 그 행 판보다 뒤에 접수된 판 하나(접수번호 · 접수일 · 재료 · 재료가 없으면 {@code null}). */
    public record LaterFiling(String rceptNo, LocalDate receipt, List<Fact> facts) {
    }

    /**
     * NC-a의 재료 — 같은 사업연도 1분기 시작과 직전 판(Q2면 Q1 · Q3면 Q2)의 기간 끝 · 사실. 1분기 · 연간 행이나 직전 판 재료가 없으면 {@code null}.
     */
    public record NcContext(LocalDate yearStart, LocalDate previousEnd, List<Fact> previousFacts) {
    }

    /** 켠 규칙 묶음. */
    public record Rules(java.util.Set<Rule> on) {

        public static final Rules NONE = new Rules(java.util.EnumSet.noneOf(Rule.class));
        /** 국내 관문 켬 — 들인 규칙 전부. */
        public static final Rules V4 = new Rules(java.util.EnumSet.allOf(Rule.class));

        public boolean has(Rule rule) {
            return on.contains(rule);
        }

        /** 그 규칙을 뺀 묶음. */
        public Rules without(Rule rule) {
            java.util.Set<Rule> rest = java.util.EnumSet.noneOf(Rule.class);
            rest.addAll(on);
            rest.remove(rule);
            return new Rules(rest);
        }
    }

    /** 그 판 재무 행의 손익 둘 — 앵커와 {@code kr-ni} 값의 출처다. */
    public record RowIncome(BigDecimal netIncomeControlling, BigDecimal netIncome) {

        /** 붙일 때의 스냅숏 — 읽는 쪽이 지금 행과 맞대는 값({@code coalesce(net_income_controlling, net_income)}). */
        public BigDecimal snapshot() {
            return netIncomeControlling != null ? netIncomeControlling : netIncome;
        }
    }

    /** 주식총수 명부(사업보고서 11011) 한 해의 줄 하나 — 받은 그대로(라벨은 판별에 안 쓴다). */
    public record ShareRow(String label, BigDecimal issued, BigDecimal treasury) {
    }

    /**
     * 한 해 명부의 요약. 「합계 · 비고를 뺀 발행수 > 0 종류 행」이 둘 이상이면 보통주 밖 주식이 있고, 보통주는 가장 큰 종류 행이다
     * (상법 제344조의3 — 의결권 없는 · 제한 주식은 발행 총수의 1/4 이하 · ADR-0192). {@code asOf}는 명부의 기준일(결산일)이다.
     */
    public record Register(LocalDate asOf, boolean multiClass, BigDecimal totalOutstanding, BigDecimal commonOutstanding,
                           BigDecimal nonCommonShare, int classCount) {

        /** 줄들에서 요약을 짓는다. 합계 줄이 없거나 종류 행이 하나도 없으면 {@code null}(명부를 못 읽음). */
        public static Register of(LocalDate asOf, List<ShareRow> rows) {
            List<ShareRow> classes = new ArrayList<>();
            ShareRow total = null;
            for (ShareRow row : rows) {
                String label = row.label() == null ? "" : row.label().strip();
                if ("합계".equals(label)) {
                    total = row;
                } else if (!"비고".equals(label) && row.issued() != null && row.issued().signum() > 0) {
                    classes.add(row);
                }
            }
            if (total == null || total.issued() == null || total.issued().signum() <= 0 || classes.isEmpty()) {
                return null;
            }
            ShareRow common = classes.get(0);
            for (ShareRow row : classes) {
                if (row.issued().compareTo(common.issued()) > 0) {
                    common = row;
                }
            }
            BigDecimal totalOut = total.issued().subtract(orZero(total.treasury()));
            BigDecimal commonOut = common.issued().subtract(orZero(common.treasury()));
            BigDecimal nonCommon = BigDecimal.ONE.subtract(common.issued().divide(total.issued(), MathContext.DECIMAL64));
            return new Register(asOf, classes.size() >= 2, totalOut, commonOut, nonCommon, classes.size());
        }
    }

    /** 기업행위 배수 — {@code (from, to]}에 효력이 난 APPLIED 사건의 곱(분할 5:1이면 5). */
    @FunctionalInterface
    public interface Multiple {
        double between(LocalDate exclusiveFrom, LocalDate inclusiveTo);
    }

    /** 판정 결과. 값이 없으면 {@code common}이 {@code null}이고 {@code branch}가 {@code none:*}다. */
    /** @param fallback 누계 갈래로도 값이 안 설 때 쓰는 그 판 판정({@link Rule#R1P}) — 없으면 {@code null} */
    public record Judged(String branch, BigDecimal common, char basis, char statement, BigDecimal tolerance,
                         BigDecimal payoutBase, String payoutBranch, boolean wantsCumulative, LocalDate comparativeReceipt,
                         Judged fallback) {

        public Judged(String branch, BigDecimal common, char basis, char statement, BigDecimal tolerance, BigDecimal payoutBase,
                      String payoutBranch, boolean wantsCumulative) {
            this(branch, common, basis, statement, tolerance, payoutBase, payoutBranch, wantsCumulative, null, null);
        }

        /** 비교열 W({@link Rule#CMP})를 쓴 판정 — 뒤 판 접수일을 단다. */
        Judged withComparative(LocalDate receipt) {
            return receipt == null ? this
                    : new Judged(branch, common, basis, statement, tolerance, payoutBase, payoutBranch, wantsCumulative, receipt, fallback);
        }

        Judged withFallback(Judged decided) {
            return new Judged(branch, common, basis, statement, tolerance, payoutBase, payoutBranch, wantsCumulative, comparativeReceipt,
                    decided);
        }

        /** 배당성향 분모가 보통주 몫에 기대는가 — 지배 NI 그대로({@code ni})면 보통주 몫의 가시일을 안 탄다. */
        public boolean payoutFollowsCommon() {
            return payoutBase != null && !"ni".equals(payoutBranch);
        }

        public boolean decided() {
            return common != null;
        }

        static Judged none(String why, char statement) {
            return new Judged("none:" + why, null, 'T', statement, null, null, "none:" + why, false);
        }
    }

    /**
     * 3개월 분기 · 연간 행 하나를 판정한다.
     *
     * @param prior    사업연도 Y−1 명부(없으면 {@code null})
     * @param same     사업연도 Y 명부(없으면 {@code null})
     */
    public static Judged judge(Period period, RowIncome row, List<Fact> facts, Register prior, Register same,
                               Multiple multiple) {
        return judge(period, row, facts, prior, same, multiple, Rules.NONE);
    }

    public static Judged judge(Period period, RowIncome row, List<Fact> facts, Register prior, Register same,
                               Multiple multiple, Rules rules) {
        return judge(period, row, facts, prior, same, multiple, rules, null);
    }

    /** @param nc NC-a 재료(Q2 · Q3 · {@link Rule#NC}) — 없으면 {@code null} */
    public static Judged judge(Period period, RowIncome row, List<Fact> facts, Register prior, Register same,
                               Multiple multiple, Rules rules, NcContext nc) {
        return judge(period, row, facts, prior, same, multiple, rules, nc, List.of());
    }

    /** @param later 그 행 판보다 뒤에 접수된 판들(접수번호 순 · {@link Rule#CMP}) */
    public static Judged judge(Period period, RowIncome row, List<Fact> facts, Register prior, Register same,
                               Multiple multiple, Rules rules, NcContext nc, List<LaterFiling> later) {
        Anchor anchor = anchor(period, row, facts, rules, nc);
        if (anchor == null) {
            // 행 지배 NI가 비었는데 연결 순이익이 행 NI와 같다 — 연결로 지은 행인데 지배 몫이 없다(매퍼 · 원장 몫 · reload-gate 국내
            // 「매퍼가 지배주주 순이익을 못 집는다」). 앵커 실패와 갈라 센다.
            boolean consolidatedRow = row.netIncomeControlling() == null && row.netIncome() != null
                    && axisless(facts, PROFIT, period, Set.of(Basis.CONSOLIDATED, Basis.NONE)).stream()
                    .anyMatch(v -> same(v, row.netIncome()))
                    && facts.stream().anyMatch(f -> f.basis() == Basis.CONSOLIDATED);
            return Judged.none(consolidatedRow ? "no-controlling" : "anchor", 'N');
        }
        Material m = new Material(facts, anchor.bases());
        boolean hybrid = m.hybrid();
        if (hybrid && rules.has(Rule.HY) && m.hybridRepaidBefore(fiscalStart(facts, period), period.end())) {
            hybrid = false;
        }
        boolean instruments = hybrid || m.preferenceConcepts() || (prior != null && prior.multiClass())
                || (same != null && same.multiClass());
        BigDecimal income = anchor.income();
        Set<BigDecimal> e = m.values(EPS, period, COMMON_MEMBERS);
        if (rules.has(Rule.SIGN)) {
            e = signed(e, income);
        }
        Set<BigDecimal> w = m.values(WEIGHTED, period, COMMON_MEMBERS);
        if (w.isEmpty() && rules.has(Rule.RB)) {
            w = m.axisWeights(period);
        }
        Register reference = prior != null ? prior : same;
        LocalDate comparative = null;
        if (rules.has(Rule.CMP) && w.isEmpty() && e.size() == 1) {
            LaterFiling found = comparativeWeight(later, anchor.statement(), e, period);
            BigDecimal cw = found == null ? null : single(new ArrayList<>(
                    new Material(found.facts(), comparativeBases(found.facts(), anchor.statement())).values(WEIGHTED, period, COMMON_MEMBERS)));
            if (cw != null && !"w-unit".equals(weightedCheck(cw, reference, period, multiple, rules))) {
                w = Set.of(cw);
                comparative = found.receipt();
            }
        }
        Judged judged = judgeWith(period, anchor, m, hybrid, instruments, income, e, w, reference, prior, same, multiple, rules)
                .withComparative(comparative);
        if (rules.has(Rule.R1P) && "none:no-w".equals(judged.branch()) && e.size() == 1) {
            BigDecimal shares = adjusted(reference, period, multiple);
            BigDecimal common = shares == null || shares.signum() == 0 ? null
                    : allocated(m, nc, period, income, e.iterator().next(), shares, reference, hybrid, multiple);
            if (common != null) {
                Payout payout = payout(hybrid, income, common, m, period, prior, same, multiple);
                judged = judged.withFallback(decided("r1+", common, 'T', anchor.statement(), HALF.multiply(shares), payout.base(),
                        payout.branch(), false));
            }
        }
        return judged;
    }

    /** {@link Rule#R1P}의 보통주 몫 — 닫히는 후보가 값으로 하나가 아니면 {@code null}. */
    private static BigDecimal allocated(Material m, NcContext nc, Period period, BigDecimal income, BigDecimal eps, BigDecimal shares,
                                        Register reference, boolean hybrid, Multiple multiple) {
        Set<BigDecimal> flows = ownHybridFlows(m.facts(), m.bases(), period.start(), period.end());
        BigDecimal h = flows.size() == 1 ? flows.iterator().next() : flows.isEmpty() ? ytdHybridFlow(m, nc, period) : null;
        Set<BigDecimal> prefEps = new LinkedHashSet<>();
        for (Fact f : m.facts()) {
            if (f.source() == Source.DART && PREF_EPS_DART.equals(f.concept()) && f.members().isEmpty()
                    && period.start().equals(f.start()) && period.end().equals(f.end())) {
                prefEps.add(normalized(f.value()));
            }
        }
        BigDecimal p = null;
        if (prefEps.size() == 1 && reference != null && reference.classCount() == 2 && reference.totalOutstanding() != null
                && reference.commonOutstanding() != null && reference.totalOutstanding().compareTo(reference.commonOutstanding()) > 0) {
            BigDecimal preferred = reference.totalOutstanding().subtract(reference.commonOutstanding())
                    .multiply(BigDecimal.valueOf(scale(reference, period, multiple)), MathContext.DECIMAL64);
            p = prefEps.iterator().next().multiply(preferred);
        }
        List<BigDecimal> candidates = new ArrayList<>();
        if (h != null) {
            candidates.add(h);
        }
        if (p != null && (h != null || !hybrid)) {
            candidates.add(p);
        }
        if (p != null && h != null) {
            candidates.add(h.add(p));
        }
        BigDecimal product = eps.multiply(shares);
        BigDecimal tolerance = HALF.multiply(shares);
        Set<BigDecimal> hit = new LinkedHashSet<>();
        for (BigDecimal a : candidates) {
            if (a.signum() != 0 && income.subtract(a).subtract(product).abs().compareTo(tolerance) <= 0) {
                hit.add(normalized(a));
            }
        }
        return hit.size() == 1 ? income.subtract(hit.iterator().next()) : null;
    }

    /** 지배 열 하나({@code EquityAttributableToOwnersOfParentMember})의 신종자본 배당 · 이자 — 그 기간 값들(절댓값). */
    private static Set<BigDecimal> ownHybridFlows(List<Fact> facts, Set<Basis> bases, LocalDate start, LocalDate end) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        for (Fact f : facts) {
            if (f.source() == Source.DART && HYBRID_FLOWS.contains(f.concept()) && bases.contains(f.basis()) && f.members().size() == 1
                    && OWNERS_COLUMN.equals(f.members().get(0).member()) && start.equals(f.start()) && end.equals(f.end())) {
                out.add(normalized(f.value().abs()));
            }
        }
        return out;
    }

    /** 반기 · 3분기 3개월 몫 = 그 판 누계 − 직전 판 누계(NC-a 재료와 같은 정의) — 어느 쪽이든 하나가 아니면 {@code null}. */
    private static BigDecimal ytdHybridFlow(Material m, NcContext nc, Period period) {
        if (nc == null || !nc.previousEnd().plusDays(1).equals(period.start())) {
            return null;
        }
        Set<BigDecimal> now = ownHybridFlows(m.facts(), m.bases(), nc.yearStart(), period.end());
        Set<BigDecimal> before = ownHybridFlows(nc.previousFacts(), m.bases(), nc.yearStart(), nc.previousEnd());
        return now.size() == 1 && before.size() == 1 ? now.iterator().next().subtract(before.iterator().next()) : null;
    }

    /** {@link Rule#CMP} — 뒤 판 중 같은 기간 W가 하나인 첫 판. 그 판의 같은 기간 E 집합이 {@code e}와 다르면 거기서 멈춘다({@code null}). */
    private static LaterFiling comparativeWeight(List<LaterFiling> later, char statement, Set<BigDecimal> e, Period period) {
        for (LaterFiling filing : later) {
            if (filing.facts() == null) {
                continue;
            }
            Material lm = new Material(filing.facts(), comparativeBases(filing.facts(), statement));
            Set<BigDecimal> fw = lm.values(WEIGHTED, period, COMMON_MEMBERS);
            if (fw.size() == 1) {
                return lm.values(EPS, period, COMMON_MEMBERS).equals(e) ? filing : null;
            }
        }
        return null;
    }

    /** 뒤 판에서 읽을 기저 — 연결 행이면 연결(축 없음 포함) · 아니면 별도(그 판에 연결 사실이 없으면 축 없음도). */
    private static Set<Basis> comparativeBases(List<Fact> facts, char statement) {
        if (statement == 'C') {
            return Set.of(Basis.CONSOLIDATED, Basis.NONE);
        }
        return facts.stream().anyMatch(f -> f.basis() == Basis.CONSOLIDATED) ? Set.of(Basis.SEPARATE) : Set.of(Basis.SEPARATE, Basis.NONE);
    }

    private static Judged judgeWith(Period period, Anchor anchor, Material m, boolean hybrid, boolean instruments, BigDecimal income,
                                    Set<BigDecimal> e, Set<BigDecimal> w, Register reference, Register prior, Register same,
                                    Multiple multiple, Rules rules) {
        if (e.size() > 1 || w.size() > 1) {
            BigDecimal[] pair = rules.has(Rule.C1) ? resolveConflict(income, e, w, instruments, m, period) : null;
            if (pair == null) {
                return Judged.none("conflict", anchor.statement());
            }
            e = Set.of(pair[0]);
            w = Set.of(pair[1]);
        }
        if (e.size() == 1 && w.size() == 1 && !(rules.has(Rule.U1) && !instruments && w.iterator().next().signum() <= 0)) {
            BigDecimal eps = e.iterator().next();
            BigDecimal weighted = w.iterator().next();
            String wrong = weightedCheck(weighted, reference, period, multiple, rules);
            if (wrong != null) {
                if (rules.has(Rule.U1) && "w-unit".equals(wrong) && !instruments
                        && income.subtract(eps.multiply(weighted)).abs().compareTo(HALF.multiply(weighted)) <= 0) {
                    return decided("kr-ni", income, 'T', anchor.statement(), HALF.multiply(weighted), income, "ni", false);
                }
                return Judged.none(wrong, anchor.statement());
            }
            BigDecimal tolerance = HALF.multiply(weighted);
            BigDecimal product = eps.multiply(weighted);
            if (!instruments && income.subtract(product).abs().compareTo(tolerance) <= 0) {
                // 지분상품이 없고 원장 EPS가 지배 NI와 반올림 안에서 닫힌다 — 배분 0 · 값은 더 정밀한 지배 NI.
                return decided("kr-ni", income, 'T', anchor.statement(), tolerance, income, "ni", false);
            }
            // 원장 보통주 EPS가 보통주 몫을 직접 밝힌다 — 지분상품 증거가 없어도 EPS × W가 지배 NI와 갈리면 그 EPS를 따른다(NAVER FY2024
            // 0.54% · 구조 추론은 EPS × W가 없을 때만 쓰는 간접 증거다 · ADR-0192).
            BigDecimal common = product;
            Set<BigDecimal> direct = m.values(DIRECT, period, COMMON_MEMBERS);
            if (direct.size() == 1 && direct.iterator().next().subtract(product).abs().compareTo(tolerance) <= 0) {
                common = direct.iterator().next();
            }
            char basis = 'T';
            Set<BigDecimal> continuing = m.values(EPS_CONTINUING, period, COMMON_MEMBERS);
            if (continuing.size() == 1 && continuing.iterator().next().compareTo(eps) != 0) {
                common = continuing.iterator().next().multiply(weighted);
                basis = 'C';
            }
            Payout payout = payout(hybrid, income, basis == 'T' ? common : product, m, period, prior, same, multiple);
            return decided("exw", common, basis, anchor.statement(), tolerance, payout.base(), payout.branch(), false);
        }
        if (instruments) {
            if (e.isEmpty()) {
                Set<BigDecimal> continuing = m.values(EPS_CONTINUING, period, COMMON_MEMBERS);
                if (rules.has(Rule.E2) && w.size() == 1 && continuing.size() == 1) {
                    BigDecimal weighted = w.iterator().next();
                    String wrong = weightedCheck(weighted, reference, period, multiple, rules);
                    if (wrong != null) {
                        return Judged.none(wrong, anchor.statement());
                    }
                    Payout payout = hybrid ? new Payout(null, "none:no-eps") : payout(false, income, null, m, period, prior, same, multiple);
                    return decided("exw", continuing.iterator().next().multiply(weighted), 'C', anchor.statement(), HALF.multiply(weighted),
                            payout.base(), payout.branch(), false);
                }
                return Judged.none("no-eps", anchor.statement());
            }
            if (rules.has(Rule.R1) && e.size() == 1) {
                // 영 여유 증인 — 지분상품이 있어도 E × 기준 명부 보통주 유통이 지배 NI와 반 주 폭 안에서 닫히면 배분 0이다(계획 C 「none:no-w」
                // 214행/38종목 · 대조군 거짓 통과 2/390 · 표본 밖 0/26). 지분상품이 실제로 몫을 가져가면 E × S가 지배 NI보다 그 몫만큼 작다.
                BigDecimal shares = adjusted(reference, period, multiple);
                BigDecimal eps = e.iterator().next();
                if (shares != null && shares.signum() != 0
                        && income.subtract(eps.multiply(shares)).abs().compareTo(HALF.multiply(shares)) <= 0) {
                    Payout payout = payout(hybrid, income, income, m, period, prior, same, multiple);
                    return decided("kr-ni", income, 'T', anchor.statement(), HALF.multiply(shares), payout.base(), payout.branch(), false);
                }
            }
            // 3개월 W가 없다 — 누계가 서는지는 행 해석이 본다(누계 몫 − 직전 보고서의 누계 몫).
            return new Judged("none:no-w", null, 'T', anchor.statement(), null, null, "none:no-w", true);
        }
        if (prior == null && same == null) {
            return Judged.none("no-register", anchor.statement());
        }
        if (e.size() == 1) {
            BigDecimal eps = e.iterator().next();
            Set<BigDecimal> outstanding = m.instantValues(OUTSTANDING, period.end(), COMMON_MEMBERS);
            BigDecimal shares = outstanding.size() == 1 ? outstanding.iterator().next()
                    : adjusted(reference, period, multiple);
            if (shares != null) {
                BigDecimal bound = HALF.multiply(shares).add(WITNESS_SLACK.multiply(eps.abs()).multiply(shares));
                if (income.subtract(eps.multiply(shares)).abs().compareTo(bound) > 0) {
                    if (rules.has(Rule.U) && intervalWitness(income, eps, period, prior, same, multiple, rules.has(Rule.PIT))) {
                        return decided("kr-ni", income, 'T', anchor.statement(), null, income, "ni", false);
                    }
                    return Judged.none("witness", anchor.statement());
                }
            }
        }
        // 지분상품이 없다 — 신종자본증권 증거도 없으므로 배당성향 분모는 지배 NI 그대로다.
        return decided("kr-ni", income, 'T', anchor.statement(), null, income, "ni", false);
    }

    /**
     * 누계 기간(사업연도 시작 ~ 행 끝)의 보통주 몫 — 3개월 W가 없는 판의 누계 갈래. 지분상품이 있는 판에서만 부른다
     * ({@link Judged#wantsCumulative()}). 값이 안 서면 {@code null}.
     */
    public static BigDecimal cumulative(Period cumulative, Period period, RowIncome row, List<Fact> facts) {
        return cumulative(cumulative, period, row, facts, Rules.NONE, null);
    }

    public static BigDecimal cumulative(Period cumulative, Period period, RowIncome row, List<Fact> facts, Rules rules, NcContext nc) {
        Anchor anchor = anchor(period, row, facts, rules, nc);
        if (anchor == null) {
            return null;
        }
        Material m = new Material(facts, anchor.bases());
        Set<BigDecimal> e = m.values(EPS, cumulative, COMMON_MEMBERS);
        Set<BigDecimal> w = m.values(WEIGHTED, cumulative, COMMON_MEMBERS);
        if (w.isEmpty() && rules.has(Rule.RB)) {
            w = m.axisWeights(cumulative);
        }
        if (e.size() != 1 || w.size() != 1) {
            return null;
        }
        return e.iterator().next().multiply(w.iterator().next());
    }

    /** U — NI ÷ (E ± 0.5) 구간이 명부 구간(앞 해 · FY 행은 그 해까지 · 기간 끝으로 옮긴 보통주 유통) ±1% 안인가. 명부 구간 폭이 5%를 넘으면 아니다. */
    /** @param pointInTime {@link Rule#PIT} — 행 해석이 그 해 명부를 이미 시점으로 걸렀으므로 분기 행에도 남은 명부를 다 쓴다 */
    private static boolean intervalWitness(BigDecimal income, BigDecimal eps, Period period, Register prior, Register same,
                                           Multiple multiple, boolean pointInTime) {
        if (eps.signum() == 0 || income.signum() == 0 || (income.signum() > 0) != (eps.signum() > 0)) {
            return false;
        }
        BigDecimal low = eps.subtract(HALF);
        BigDecimal high = eps.add(HALF);
        if (low.signum() <= 0 && high.signum() >= 0) {
            return false;
        }
        BigDecimal a = income.divide(low, MathContext.DECIMAL64);
        BigDecimal b = income.divide(high, MathContext.DECIMAL64);
        BigDecimal ivLow = a.min(b);
        BigDecimal ivHigh = a.max(b);
        boolean annual = period.start().plusYears(1).equals(period.end().plusDays(1));
        List<BigDecimal> registers = new ArrayList<>();
        for (Register g : annual || pointInTime ? java.util.Arrays.asList(prior, same) : java.util.Collections.singletonList(prior)) {
            BigDecimal adjusted = g == null ? null : adjusted(g, period, multiple);
            if (adjusted != null && adjusted.signum() != 0) {
                registers.add(adjusted);
            }
        }
        if (registers.isEmpty()) {
            return false;
        }
        BigDecimal lo = java.util.Collections.min(registers);
        BigDecimal hi = java.util.Collections.max(registers);
        if (hi.subtract(lo).compareTo(WITNESS_SLACK.multiply(lo)) > 0) {
            return false;
        }
        double gap = ivHigh.compareTo(lo) < 0 ? lo.subtract(ivHigh).divide(lo, MathContext.DECIMAL64).doubleValue()
                : ivLow.compareTo(hi) > 0 ? ivLow.subtract(hi).divide(hi, MathContext.DECIMAL64).doubleValue() : 0.0;
        return gap <= 0.01;
    }

    private static Judged decided(String branch, BigDecimal common, char basis, char statement, BigDecimal tolerance,
                                  BigDecimal payoutBase, String payoutBranch, boolean wantsCumulative) {
        return new Judged(branch, common, basis, statement, tolerance, payoutBase, payoutBranch, wantsCumulative);
    }

    /** W의 큰 오류 둘 — 단위 · 주식-일({@code w-unit}) · 구형우선주를 보통주에 섞음({@code w-includes-pref}). 괜찮으면 {@code null}. */
    private static String weightedCheck(BigDecimal weighted, Register reference, Period period, Multiple multiple, Rules rules) {
        BigDecimal base = adjusted(reference, period, multiple);
        if (base == null || base.signum() <= 0) {
            return null;
        }
        double ratio = weighted.divide(base, MathContext.DECIMAL64).doubleValue();
        if (ratio < W_RATIO_MIN || ratio > W_RATIO_MAX) {
            return "w-unit";
        }
        if (reference.multiClass() && reference.totalOutstanding().signum() > 0) {
            double mixed = weighted.divide(reference.totalOutstanding(), MathContext.DECIMAL64).doubleValue()
                    / scale(reference, period, multiple);
            if (Math.abs(mixed - 1) <= MIXED_PREF_BAND && reference.nonCommonShare().doubleValue() > MIXED_PREF_BAND
                    && !(rules.has(Rule.P1) && reference.totalOutstanding().subtract(reference.commonOutstanding())
                    .divide(reference.totalOutstanding(), MathContext.DECIMAL64).doubleValue() <= MIXED_PREF_BAND)) {
                return "w-includes-pref";
            }
        }
        return null;
    }

    /** 명부 보통주 유통을 기간 끝으로 옮긴 값 — 명부 기준일과 기간 끝 사이 기업행위 배수를 곱하거나 나눈다. */
    private static BigDecimal adjusted(Register register, Period period, Multiple multiple) {
        if (register == null || register.commonOutstanding() == null) {
            return null;
        }
        return register.commonOutstanding().multiply(BigDecimal.valueOf(scale(register, period, multiple)), MathContext.DECIMAL64);
    }

    private static double scale(Register register, Period period, Multiple multiple) {
        if (register.asOf() == null || multiple == null) {
            return 1.0;
        }
        if (register.asOf().isBefore(period.end())) {
            return multiple.between(register.asOf(), period.end());
        }
        if (register.asOf().isAfter(period.end())) {
            return 1.0 / multiple.between(period.end(), register.asOf());
        }
        return 1.0;
    }

    private record Payout(BigDecimal base, String branch) {
    }

    /**
     * 배당성향 분모(사용자 결정 「지배 NI − 신종자본증권 몫」) — 신종자본증권 증거가 없으면 지배 NI · 있으면 보통주 몫(중단 포함) +
     * 우선주 몫. 우선주 몫은 명부가 한 종류면 0, 아니면 우선주 종류마다 E_p × W_p의 합이고 Σ W_p를 명부 보통주 밖 유통과 맞댄다.
     */
    private static Payout payout(boolean hybrid, BigDecimal income, BigDecimal commonTotal, Material m, Period period,
                                 Register prior, Register same, Multiple multiple) {
        if (!hybrid) {
            return new Payout(income, "ni");
        }
        Register reference = prior != null ? prior : same;
        if (reference != null && !reference.multiClass() && (same == null || !same.multiClass())) {
            return new Payout(commonTotal, "c+0");
        }
        Map<String, Set<BigDecimal>> eps = new LinkedHashMap<>();
        Map<String, Set<BigDecimal>> weighted = new LinkedHashMap<>();
        for (Fact f : m.facts()) {
            if (!period.start().equals(f.start()) || !period.end().equals(f.end())) {
                continue;
            }
            boolean prefEps = f.source() == Source.DART && PREF_EPS_DART.equals(f.concept())
                    || f.source() == Source.IFRS && PREF_EPS_IFRS.equals(f.concept());
            boolean prefWeighted = f.source() == Source.IFRS && PREF_WEIGHTED.equals(f.concept());
            if (!prefEps && !prefWeighted) {
                continue;
            }
            String key = classKey(f);
            if (key == null) {
                continue;
            }
            if ("OrdinarySharesMember".equals(key)) {
                return new Payout(null, "none:pref-mistag");
            }
            (prefEps ? eps : weighted).computeIfAbsent(key, k -> new LinkedHashSet<>()).add(normalized(f.value()));
        }
        Set<String> classes = new LinkedHashSet<>(eps.keySet());
        classes.addAll(weighted.keySet());
        if (classes.size() > 1) {
            classes.remove(AXISLESS_CLASS);   // 종류별 사실이 있으면 축 없는 합계 사실은 그 합의 사본이다
        }
        if (classes.isEmpty()) {
            return new Payout(null, "none:pref-absent");
        }
        BigDecimal preferred = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (String key : classes) {
            Set<BigDecimal> e = eps.getOrDefault(key, Set.of());
            Set<BigDecimal> w = weighted.getOrDefault(key, Set.of());
            if (e.size() != 1 || w.size() != 1 || w.iterator().next().signum() <= 0) {
                return new Payout(null, "none:pref-class");
            }
            preferred = preferred.add(e.iterator().next().multiply(w.iterator().next()));
            weightedSum = weightedSum.add(w.iterator().next());
        }
        if (reference != null && reference.totalOutstanding() != null && reference.commonOutstanding() != null) {
            BigDecimal nonCommon = reference.totalOutstanding().subtract(reference.commonOutstanding())
                    .multiply(BigDecimal.valueOf(scale(reference, period, multiple)), MathContext.DECIMAL64);
            if (nonCommon.signum() > 0) {
                double ratio = weightedSum.divide(nonCommon, MathContext.DECIMAL64).doubleValue();
                if (ratio < W_RATIO_MIN || ratio > W_RATIO_MAX) {
                    return new Payout(null, "none:pref-w");
                }
            }
        }
        return new Payout(commonTotal.add(preferred), "c+p");
    }

    /** 우선 사실의 종류 열쇠 — 종류 축 구성원 · 축 없으면 {@link #AXISLESS_CLASS} · 다른 축이 붙으면 {@code null}(안 받음). */
    private static String classKey(Fact f) {
        String key = AXISLESS_CLASS;
        for (Member member : f.members()) {
            if (CLASS_AXIS.equals(member.axis())) {
                key = member.member();
                continue;
            }
            String pass = PASS_THROUGH_AXES.get(member.axis());
            if (pass == null || !pass.equals(member.member()) || member.source() == Source.ENTITY) {
                return null;
            }
        }
        return key;
    }

    private record Anchor(BigDecimal income, Set<Basis> bases, char statement) {
    }

    /**
     * 앵커 — 행의 손익이 그 판 인스턴스의 어느 기저 맥락과 같은가. 행 지배 NI가 있으면 연결 지배 귀속(그 태그가 없으면 연결 순이익),
     * 지배 NULL이면 별도(연결 맥락이 없는 파일은 축 없는) 순이익. 1원 또는 상대 1e−9 안을 같다고 본다.
     */
    /** 3판 앵커 · 없으면 4판 대체 앵커(NC). */
    private static Anchor anchor(Period period, RowIncome row, List<Fact> facts, Rules rules, NcContext nc) {
        Anchor anchor = anchor(period, row, facts);
        if (anchor != null) {
            return anchor;
        }
        if (rules.has(Rule.NC) && consolidatedRow(period, row, facts)) {
            Anchor alternative = nc == null ? null : cumulativeDifference(period, facts, nc);
            alternative = alternative != null ? alternative : noNoncontrolling(period, row, facts);
            if (alternative != null) {
                return alternative;
            }
        }
        return rules.has(Rule.AN3) ? ownersBesideNoncontrolling(period, row, facts) : null;
    }

    /** AN3 — 행 지배 NI = 연결 순이익(하나)이고 표준 지배 귀속 + 표준 비지배 귀속 = 행 지배 NI면 앵커는 지배 귀속이다. */
    private static Anchor ownersBesideNoncontrolling(Period period, RowIncome row, List<Fact> facts) {
        if (row.netIncomeControlling() == null) {
            return null;
        }
        Set<Basis> consolidated = Set.of(Basis.CONSOLIDATED, Basis.NONE);
        BigDecimal profit = single(axisless(facts, PROFIT, period, consolidated));
        if (profit == null || !same(profit, row.netIncomeControlling())) {
            return null;
        }
        BigDecimal owners = single(owners(facts, period, consolidated));
        List<BigDecimal> noncontrolling = new ArrayList<>(axisless(facts, NONCONTROLLING, period, consolidated));
        for (Fact f : facts) {
            if (f.source() == Source.IFRS && PROFIT.equals(f.concept()) && consolidated.contains(f.basis()) && period.start().equals(f.start())
                    && period.end().equals(f.end()) && f.members().size() == 1
                    && "NoncontrollingInterestsMember".equals(f.members().get(0).member()) && f.members().get(0).source() != Source.ENTITY) {
                noncontrolling.add(f.value());
            }
        }
        BigDecimal minority = single(noncontrolling);
        if (owners == null || minority == null || !same(owners.add(minority), row.netIncomeControlling())) {
            return null;
        }
        return new Anchor(owners, consolidated, 'C');
    }

    /** 행 지배 NI가 비었는데 연결 순이익이 행 NI와 같다 — 연결로 지은 행인데 지배 몫이 없다(no-controlling). */
    private static boolean consolidatedRow(Period period, RowIncome row, List<Fact> facts) {
        return row.netIncomeControlling() == null && row.netIncome() != null
                && axisless(facts, PROFIT, period, Set.of(Basis.CONSOLIDATED, Basis.NONE)).stream().anyMatch(v -> same(v, row.netIncome()))
                && facts.stream().anyMatch(f -> f.basis() == Basis.CONSOLIDATED);
    }

    /** NC-a — 누계 지배 귀속 차(그 판 · 직전 판)가 3개월 지배 NI다. 연결 순이익의 누계 차가 3개월 연결 순이익과 닫혀야 한다(소급 재작성 가드). */
    private static Anchor cumulativeDifference(Period period, List<Fact> facts, NcContext nc) {
        if (nc.previousFacts() == null) {
            return null;
        }
        Set<Basis> consolidated = Set.of(Basis.CONSOLIDATED, Basis.NONE);
        Period cumulative = new Period(nc.yearStart(), period.end());
        Period previous = new Period(nc.yearStart(), nc.previousEnd());
        BigDecimal cum = single(owners(facts, cumulative, consolidated));
        BigDecimal earlier = single(owners(nc.previousFacts(), previous, consolidated));
        BigDecimal plCum = single(axisless(facts, PROFIT, cumulative, consolidated));
        BigDecimal plPrevious = single(axisless(nc.previousFacts(), PROFIT, previous, consolidated));
        BigDecimal plQuarter = single(axisless(facts, PROFIT, period, consolidated));
        if (cum == null || earlier == null || plCum == null || plPrevious == null || plQuarter == null
                || !same(plCum.subtract(plPrevious), plQuarter)) {
            return null;
        }
        return new Anchor(cum.subtract(earlier), consolidated, 'C');
    }

    /** NC-b — 기초 · 기말 자본총계가 지배 자본과 같고 0 아닌 비지배 손익 줄이 없으면 비지배가 없다 — 연결 순이익이 지배 NI다. */
    private static Anchor noNoncontrolling(Period period, RowIncome row, List<Fact> facts) {
        LocalDate priorEnd = period.start().getMonthValue() == 1 ? LocalDate.of(period.start().getYear() - 1, 12, 31)
                : period.start().minusDays(1);
        if (!equityIsOwners(facts, period.end()) || !equityIsOwners(facts, priorEnd)) {
            return null;
        }
        Set<Basis> consolidated = Set.of(Basis.CONSOLIDATED, Basis.NONE);
        for (Fact f : facts) {
            if (f.source() == Source.IFRS && "ProfitLossAttributableToNoncontrollingInterests".equals(f.concept())
                    && f.value().signum() != 0 && consolidated.contains(f.basis())) {
                return null;
            }
        }
        return new Anchor(row.netIncome(), consolidated, 'C');
    }

    private static boolean equityIsOwners(List<Fact> facts, LocalDate at) {
        Set<BigDecimal> equity = new LinkedHashSet<>();
        Set<BigDecimal> owners = new LinkedHashSet<>();
        for (Fact f : facts) {
            if (f.source() != Source.IFRS || f.start() != null || !at.equals(f.end()) || !f.members().isEmpty()
                    || (f.basis() != Basis.CONSOLIDATED && f.basis() != Basis.NONE)) {
                continue;
            }
            if ("Equity".equals(f.concept())) {
                equity.add(normalized(f.value()));
            } else if ("EquityAttributableToOwnersOfParent".equals(f.concept())) {
                owners.add(normalized(f.value()));
            }
        }
        return equity.size() == 1 && equity.equals(owners);
    }

    /** 정규화한 값이 하나면 그것 · 없거나 여럿이면 {@code null}. */
    private static BigDecimal single(List<BigDecimal> values) {
        Set<BigDecimal> distinct = new LinkedHashSet<>();
        values.forEach(v -> distinct.add(normalized(v)));
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    private static Anchor anchor(Period period, RowIncome row, List<Fact> facts) {
        boolean hasConsolidated = facts.stream().anyMatch(f -> f.basis() == Basis.CONSOLIDATED);
        Set<Basis> consolidated = Set.of(Basis.CONSOLIDATED, Basis.NONE);
        if (row.netIncomeControlling() != null) {
            List<BigDecimal> owners = owners(facts, period, consolidated);
            if (owners.stream().anyMatch(v -> same(v, row.netIncomeControlling()))) {
                return new Anchor(row.netIncomeControlling(), consolidated, 'C');
            }
            // 그 판이 지배 귀속을 표준으로 안 주면 행이 이 판의 연결 맥락인지만 묻는다 — 연결 순이익이 행 지배 NI(비지배 0)나 행 NI(비지배 ≠ 0 ·
            // 매퍼가 지배 + 비지배 = 순이익으로 확인한 비표준 줄 · reload-gate 국내 「매퍼가 지배주주 순이익을 못 집는다」)와 같으면 행 지배 NI를 받는다.
            if (owners.isEmpty() && axisless(facts, PROFIT, period, consolidated).stream()
                    .anyMatch(v -> same(v, row.netIncomeControlling())
                            || row.netIncome() != null && same(v, row.netIncome()))) {
                return new Anchor(row.netIncomeControlling(), consolidated, 'C');
            }
            return null;
        }
        if (row.netIncome() == null) {
            return null;
        }
        if (hasConsolidated && axisless(facts, PROFIT, period, consolidated).stream().anyMatch(v -> same(v, row.netIncome()))) {
            // 연결로 지은 행인데 지배 몫이 비었다 — 그 판이 같은 기간 지배 귀속을 표준으로 주면 그 값이 지배 NI다(원장 조합).
            Set<BigDecimal> owners = new LinkedHashSet<>();
            owners(facts, period, consolidated).forEach(v -> owners.add(normalized(v)));
            return owners.size() == 1 ? new Anchor(owners.iterator().next(), consolidated, 'C') : null;
        }
        Set<Basis> separate = hasConsolidated ? Set.of(Basis.SEPARATE) : Set.of(Basis.SEPARATE, Basis.NONE);
        if (axisless(facts, PROFIT, period, separate).stream().anyMatch(v -> same(v, row.netIncome()))) {
            return new Anchor(row.netIncome(), separate, 'S');
        }
        return null;
    }

    /**
     * 그 기간 연결 지배 귀속 순이익 — 손익계산서의 표준 귀속 줄, 그 줄이 없는 판(NAVER 2018 반기 등)은 자본변동표의 표준 축
     * ({@code ComponentsOfEquityAxis} × {@code EquityAttributableToOwnersOfParentMember})으로 단 순이익.
     */
    private static List<BigDecimal> owners(List<Fact> facts, Period period, Set<Basis> consolidated) {
        List<BigDecimal> owners = new ArrayList<>(axisless(facts, OWNERS, period, consolidated));
        for (Fact f : facts) {
            if (f.source() == Source.IFRS && PROFIT.equals(f.concept()) && consolidated.contains(f.basis())
                    && period.start().equals(f.start()) && period.end().equals(f.end()) && f.members().size() == 1
                    && "ComponentsOfEquityAxis".equals(f.members().get(0).axis())
                    && "EquityAttributableToOwnersOfParentMember".equals(f.members().get(0).member())
                    && f.members().get(0).source() != Source.ENTITY) {
                owners.add(f.value());
            }
        }
        return owners;
    }

    private static List<BigDecimal> axisless(List<Fact> facts, String concept, Period period, Set<Basis> bases) {
        List<BigDecimal> out = new ArrayList<>();
        for (Fact f : facts) {
            if (f.source() == Source.IFRS && concept.equals(f.concept()) && bases.contains(f.basis()) && f.members().isEmpty()
                    && period.start().equals(f.start()) && period.end().equals(f.end())) {
                out.add(f.value());
            }
        }
        return out;
    }

    /** 그 판의 사업연도 시작 — 기간 끝이 같은 기간 사실 중 가장 이른 시작(누계 열 · 거르지 않은 전체 사실). 없으면 기간 시작. */
    private static LocalDate fiscalStart(List<Fact> facts, Period period) {
        LocalDate start = null;
        for (Fact f : facts) {
            if (f.start() != null && period.end().equals(f.end()) && (start == null || f.start().isBefore(start))) {
                start = f.start();
            }
        }
        return start != null ? start : period.start();
    }

    /** {@link Rule#SIGN}으로 거른 총 E 후보. */
    private static Set<BigDecimal> signed(Set<BigDecimal> e, BigDecimal income) {
        Set<BigDecimal> kept = new LinkedHashSet<>();
        for (BigDecimal x : e) {
            if (x.signum() == 0 || income.signum() == 0 || !(x.signum() > 0 && income.signum() < 0)) {
                kept.add(x);
            }
        }
        if (kept.size() > 1) {
            Set<BigDecimal> same = new LinkedHashSet<>();
            for (BigDecimal x : kept) {
                if (x.signum() == 0 || income.signum() == 0 || x.signum() == income.signum()) {
                    same.add(x);
                }
            }
            if (!same.isEmpty()) {
                return same;
            }
        }
        return kept;
    }

    /** {@link Rule#C1}의 짝 {E, W} — 못 고르면 {@code null}. */
    private static BigDecimal[] resolveConflict(BigDecimal income, Set<BigDecimal> e, Set<BigDecimal> w, boolean instruments,
                                                Material m, Period period) {
        BigDecimal[] best = null;
        BigDecimal bestResidual = null;
        BigDecimal low = null;
        BigDecimal high = null;
        for (BigDecimal x : e) {
            for (BigDecimal y : w) {
                BigDecimal residual = income.subtract(x.multiply(y)).abs();
                if (residual.compareTo(HALF.multiply(y)) > 0) {
                    continue;
                }
                low = low == null || x.compareTo(low) < 0 ? x : low;
                high = high == null || x.compareTo(high) > 0 ? x : high;
                // 잔차가 같으면 작은 E · 작은 W(기준 구현의 튜플 최소와 같은 순서)
                int order = bestResidual == null ? -1 : residual.compareTo(bestResidual);
                if (order == 0) {
                    order = x.compareTo(best[0]);
                    order = order != 0 ? order : y.compareTo(best[1]);
                }
                if (order < 0) {
                    best = new BigDecimal[]{x, y};
                    bestResidual = residual;
                }
            }
        }
        if (best == null || high.subtract(low).compareTo(BigDecimal.ONE) >= 0) {
            return null;
        }
        if (instruments) {
            if (w.size() > 1) {
                return null;
            }
            Set<BigDecimal> diluted = m.values(EPS_DILUTED, period, COMMON_MEMBERS);
            for (BigDecimal dropped : e) {
                if (dropped.subtract(best[0]).abs().compareTo(BigDecimal.ONE) < 0) {
                    continue;
                }
                if (dropped.compareTo(best[0]) >= 0 || diluted.stream().noneMatch(d -> d.compareTo(dropped) == 0)) {
                    return null;
                }
            }
        }
        return best;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        BigDecimal gap = a.subtract(b).abs();
        return gap.compareTo(BigDecimal.ONE) <= 0
                || gap.compareTo(b.abs().multiply(new BigDecimal("1e-9"))) <= 0;
    }

    private static BigDecimal normalized(BigDecimal v) {
        return v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros();
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 앵커가 정한 기저의 사실만 보는 창. */
    private record Material(List<Fact> facts, Set<Basis> bases) {

        Material {
            facts = facts.stream().filter(f -> bases.contains(f.basis())).toList();
        }

        /** 같은 기간 · 허용 구성원(이름과 표준 이름공간 둘 다)만 단 IFRS 사실의 값들. */
        Set<BigDecimal> values(String concept, Period period, Set<String> allowed) {
            Set<BigDecimal> out = new LinkedHashSet<>();
            for (Fact f : facts) {
                if (f.source() == Source.IFRS && concept.equals(f.concept()) && period.start().equals(f.start())
                        && period.end().equals(f.end()) && standardMembers(f, allowed)) {
                    out.add(normalized(f.value()));
                }
            }
            return out;
        }

        Set<BigDecimal> instantValues(String concept, LocalDate instant, Set<String> allowed) {
            Set<BigDecimal> out = new LinkedHashSet<>();
            for (Fact f : facts) {
                if (f.source() == Source.IFRS && concept.equals(f.concept()) && f.instant() && instant.equals(f.end())
                        && standardMembers(f, allowed)) {
                    out.add(normalized(f.value()));
                }
            }
            return out;
        }

        /** 신종자본증권 증거 — DART 표준 개념 중 이름에 {@code Hybrid}가 든 것의 0 아닌 값(비지배 구성원 맥락 밖 · 기간 무관). */
        boolean hybrid() {
            for (Fact f : facts) {
                if (hybridEvidence(f)) {
                    return true;
                }
            }
            return false;
        }

        /** {@link Rule#RB}의 W 후보. */
        Set<BigDecimal> axisWeights(Period period) {
            Map<String, Set<BigDecimal>> classes = new LinkedHashMap<>();
            Map<String, Set<BigDecimal>> operations = new LinkedHashMap<>();
            for (Fact f : facts) {
                if (f.source() != Source.IFRS || !WEIGHTED.equals(f.concept()) || !period.start().equals(f.start())
                        || !period.end().equals(f.end()) || f.members().size() != 1) {
                    continue;
                }
                Member member = f.members().get(0);
                Map<String, Set<BigDecimal>> axis = switch (member.axis()) {
                    case "ClassesOfOrdinarySharesAxis" -> classes;
                    case "ContinuingAndDiscontinuedOperationsAxis" -> operations;
                    default -> null;
                };
                if (axis != null) {
                    axis.computeIfAbsent(member.member(), k -> new LinkedHashSet<>()).add(normalized(f.value()));
                }
            }
            Set<BigDecimal> out = new LinkedHashSet<>();
            if (classes.size() == 1 && classes.values().iterator().next().size() == 1) {
                out.addAll(classes.values().iterator().next());
            }
            if (operations.size() == 2 && operations.values().stream().allMatch(v -> v.size() == 1)) {
                Set<BigDecimal> union = new LinkedHashSet<>();
                operations.values().forEach(union::addAll);
                if (union.size() == 1) {
                    out.addAll(union);
                }
            }
            return out;
        }

        /** {@link Rule#HY} — 신종자본 증거가 당기 열에 없고 앞 열에 상환 개념이 있는가. */
        boolean hybridRepaidBefore(LocalDate fiscalStart, LocalDate end) {
            boolean repayment = false;
            for (Fact f : facts) {
                if (!hybridEvidence(f)) {
                    continue;
                }
                if (f.end().isAfter(fiscalStart) || f.instant() && f.end().equals(end)) {
                    return false;
                }
                repayment |= f.concept().contains("Repayment");
            }
            return repayment;
        }

        private static boolean hybridEvidence(Fact f) {
            return f.source() == Source.DART && f.concept().contains("Hybrid") && f.value().signum() != 0
                    && f.members().stream().noneMatch(mb -> "NonControllingInterestsMember".equals(mb.member()));
        }

        /** 표준 우선 개념의 0 아닌 값 — 보통주 밖 주식의 증거(기간 무관). */
        boolean preferenceConcepts() {
            return KrCommonIncome.preferenceConcepts(facts);
        }
    }

    /** 표준 우선 개념의 0 아닌 값 — 보통주 밖 주식의 증거(기간 무관 · 판정 엔진과 자본 판정이 같은 술어를 쓴다). */
    static boolean preferenceConcepts(List<Fact> facts) {
        for (Fact f : facts) {
            if (f.value().signum() == 0) {
                continue;
            }
            if (f.source() == Source.DART && (f.concept().startsWith(PREF_EPS_DART)
                    || f.concept().startsWith("DilutedEarningsLossPerSharePreferredStock")
                    || "IssuedCapitalOfPreferredStock".equals(f.concept()))) {
                return true;
            }
            if (f.source() == Source.IFRS && f.concept().contains("OtherThanOrdinaryShares")
                    && (f.concept().contains("EarningsLossPerInstrument")
                    || f.concept().startsWith("WeightedAverageNumberOfInstruments"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean standardMembers(Fact f, Set<String> allowed) {
        for (Member member : f.members()) {
            if (member.source() == Source.ENTITY || !allowed.contains(member.member())) {
                return false;
            }
        }
        return true;
    }
}
