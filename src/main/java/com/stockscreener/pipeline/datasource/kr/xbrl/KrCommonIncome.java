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
    public static final int ENGINE_VERSION = 1;

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
    private static final String WEIGHTED = "WeightedAverageShares";
    private static final String DIRECT = "ProfitLossAttributableToOrdinaryEquityHoldersOfParentEntity";
    private static final String OWNERS = "ProfitLossAttributableToOwnersOfParent";
    private static final String PROFIT = "ProfitLoss";
    private static final String OUTSTANDING = "NumberOfSharesOutstanding";
    private static final String PREF_EPS_DART = "BasicEarningsLossPerSharePreferredStock";
    private static final String PREF_EPS_IFRS = "BasicEarningsLossPerInstrumentParticipatingEquityInstrumentsOtherThanOrdinaryShares";
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
                           BigDecimal nonCommonShare) {

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
            return new Register(asOf, classes.size() >= 2, totalOut, commonOut, nonCommon);
        }
    }

    /** 기업행위 배수 — {@code (from, to]}에 효력이 난 APPLIED 사건의 곱(분할 5:1이면 5). */
    @FunctionalInterface
    public interface Multiple {
        double between(LocalDate exclusiveFrom, LocalDate inclusiveTo);
    }

    /** 판정 결과. 값이 없으면 {@code common}이 {@code null}이고 {@code branch}가 {@code none:*}다. */
    public record Judged(String branch, BigDecimal common, char basis, char statement, BigDecimal tolerance,
                         BigDecimal payoutBase, String payoutBranch, boolean wantsCumulative) {

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
        Anchor anchor = anchor(period, row, facts);
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
        boolean instruments = hybrid || m.preferenceConcepts() || (prior != null && prior.multiClass())
                || (same != null && same.multiClass());
        BigDecimal income = anchor.income();
        Set<BigDecimal> e = m.values(EPS, period, COMMON_MEMBERS);
        Set<BigDecimal> w = m.values(WEIGHTED, period, COMMON_MEMBERS);
        if (e.size() > 1 || w.size() > 1) {
            return Judged.none("conflict", anchor.statement());
        }
        Register reference = prior != null ? prior : same;
        if (e.size() == 1 && w.size() == 1) {
            BigDecimal eps = e.iterator().next();
            BigDecimal weighted = w.iterator().next();
            String wrong = weightedCheck(weighted, reference, period, multiple);
            if (wrong != null) {
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
                return Judged.none("no-eps", anchor.statement());
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
        Anchor anchor = anchor(period, row, facts);
        if (anchor == null) {
            return null;
        }
        Material m = new Material(facts, anchor.bases());
        Set<BigDecimal> e = m.values(EPS, cumulative, COMMON_MEMBERS);
        Set<BigDecimal> w = m.values(WEIGHTED, cumulative, COMMON_MEMBERS);
        if (e.size() != 1 || w.size() != 1) {
            return null;
        }
        return e.iterator().next().multiply(w.iterator().next());
    }

    private static Judged decided(String branch, BigDecimal common, char basis, char statement, BigDecimal tolerance,
                                  BigDecimal payoutBase, String payoutBranch, boolean wantsCumulative) {
        return new Judged(branch, common, basis, statement, tolerance, payoutBase, payoutBranch, wantsCumulative);
    }

    /** W의 큰 오류 둘 — 단위 · 주식-일({@code w-unit}) · 구형우선주를 보통주에 섞음({@code w-includes-pref}). 괜찮으면 {@code null}. */
    private static String weightedCheck(BigDecimal weighted, Register reference, Period period, Multiple multiple) {
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
            if (Math.abs(mixed - 1) <= MIXED_PREF_BAND && reference.nonCommonShare().doubleValue() > MIXED_PREF_BAND) {
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
    private static Anchor anchor(Period period, RowIncome row, List<Fact> facts) {
        boolean hasConsolidated = facts.stream().anyMatch(f -> f.basis() == Basis.CONSOLIDATED);
        Set<Basis> consolidated = Set.of(Basis.CONSOLIDATED, Basis.NONE);
        if (row.netIncomeControlling() != null) {
            List<BigDecimal> owners = owners(facts, period, consolidated);
            if (owners.stream().anyMatch(v -> same(v, row.netIncomeControlling()))) {
                return new Anchor(row.netIncomeControlling(), consolidated, 'C');
            }
            if (owners.isEmpty() && axisless(facts, PROFIT, period, consolidated).stream()
                    .anyMatch(v -> same(v, row.netIncomeControlling()))) {
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
                if (f.source() == Source.DART && f.concept().contains("Hybrid") && f.value().signum() != 0
                        && f.members().stream().noneMatch(mb -> "NonControllingInterestsMember".equals(mb.member()))) {
                    return true;
                }
            }
            return false;
        }

        /** 표준 우선 개념의 0 아닌 값 — 보통주 밖 주식의 증거(기간 무관). */
        boolean preferenceConcepts() {
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
}
